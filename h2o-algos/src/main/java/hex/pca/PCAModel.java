package hex.pca;

import hex.*;
import water.DKV;
import water.Key;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.fvec.Vec;
import water.util.JCodeGen;
import water.util.SB;
import water.util.TwoDimTable;

public class PCAModel extends Model<PCAModel,PCAModel.PCAParameters,PCAModel.PCAOutput> {

  public static class PCAParameters extends Model.Parameters {
    public DataInfo.TransformType _transform = DataInfo.TransformType.NONE; // Data transformation (demean to compare with PCA)
    public int _k = 1;                // Number of principal components
    public int _max_iterations = 1000;     // Max iterations
    public long _seed = System.nanoTime(); // RNG seed
    // public Key<Frame> _loading_key;
    public String _loading_name;
    public boolean _keep_loading = true;
    public boolean _use_all_factor_levels = false;   // When expanding categoricals, should first level be kept or dropped?
  }

  public static class PCAOutput extends Model.Output {
    // Principal components (eigenvectors)
    public double[/*feature*/][/*k*/] _eigenvectors_raw;
    public TwoDimTable _eigenvectors;

    // Standard deviation of each principal component
    public double[] _std_deviation;

    // Importance of principal components
    // Standard deviation, proportion of variance explained, and cumulative proportion of variance explained
    public TwoDimTable _pc_importance;

    // Number of categorical and numeric columns
    public int _ncats;
    public int _nnums;

    // Categorical offset vector
    public int[] _catOffsets;

    // If standardized, mean of each numeric data column
    public double[] _normSub;

    // If standardized, one over standard deviation of each numeric data column
    public double[] _normMul;

    // Permutation matrix mapping training col indices to adaptedFrame
    public int[] _permutation;

    // Frame key for right singular vectors from SVD
    public Key<Frame> _loading_key;

    public PCAOutput(PCA b) { super(b); }

    /** Override because base class implements ncols-1 for features with the
     *  last column as a response variable; for PCA all the columns are
     *  features. */
    @Override public int nfeatures() { return _names.length; }

    @Override public ModelCategory getModelCategory() {
      return ModelCategory.DimReduction;
    }
  }

  public PCAModel(Key selfKey, PCAParameters parms, PCAOutput output) { super(selfKey,parms,output); }

  @Override
  public ModelMetrics.MetricBuilder makeMetricBuilder(String[] domain) {
    return new ModelMetricsPCA.PCAModelMetrics(_parms._k);
  }

  @Override
  protected Frame scoreImpl(Frame orig, Frame adaptedFr, String destination_key) {
    Frame adaptFrm = new Frame(adaptedFr);
    for(int i = 0; i < _parms._k; i++)
      adaptFrm.add("PC"+String.valueOf(i+1),adaptFrm.anyVec().makeZero());

    new MRTask() {
      @Override public void map( Chunk chks[] ) {
        double tmp [] = new double[_output._names.length];
        double preds[] = new double[_parms._k];
        for( int row = 0; row < chks[0]._len; row++) {
          double p[] = score0(chks, row, tmp, preds);
          for( int c=0; c<preds.length; c++ )
            chks[_output._names.length+c].set(row, p[c]);
        }
      }
    }.doAll(adaptFrm);

    // Return the projection into principal component space
    int x = _output._names.length, y = adaptFrm.numCols();
    Frame f = adaptFrm.extractFrame(x, y); // this will call vec_impl() and we cannot call the delete() below just yet

    f = new Frame((null == destination_key ? Key.make() : Key.make(destination_key)), f.names(), f.vecs());
    DKV.put(f);
    makeMetricBuilder(null).makeModelMetrics(this, orig);
    return f;
  }

  @Override
  protected double[] score0(double data[/*ncols*/], double preds[/*k*/]) {
    int numStart = _output._catOffsets[_output._catOffsets.length-1];
    assert data.length == _output._nnums + _output._ncats;

    for(int i = 0; i < _parms._k; i++) {
      preds[i] = 0;
      for (int j = 0; j < _output._ncats; j++) {
        double tmp = data[_output._permutation[j]];
        int last_cat = _output._catOffsets[j+1]-_output._catOffsets[j]-1;   // Missing categorical values are mapped to extra (last) factor
        int level = Double.isNaN(tmp) ? last_cat : (int)tmp - (_parms._use_all_factor_levels ? 0:1);  // Reduce index by 1 if first factor level dropped during training
        if (level < 0 || level > last_cat) continue;  // Skip categorical level in test set but not in train
        preds[i] += _output._eigenvectors_raw[_output._catOffsets[j]+level][i];
      }

      int dcol = _output._ncats;
      int vcol = numStart;
      for (int j = 0; j < _output._nnums; j++) {
        preds[i] += (data[_output._permutation[dcol]] - _output._normSub[j]) * _output._normMul[j] * _output._eigenvectors_raw[vcol][i];
        dcol++; vcol++;
      }
    }
    return preds;
  }

  @Override
  public Frame score(Frame fr, String destination_key) {
    Frame adaptFr = new Frame(fr);
    adaptTestForTrain(adaptFr, true, false);   // Adapt
    Frame output = scoreImpl(fr, adaptFr, destination_key); // Score
    cleanup_adapt( adaptFr, fr );
    return output;
  }

  @Override protected SB toJavaInit(SB sb, SB fileContextSB) {
    sb = super.toJavaInit(sb, fileContextSB);
    sb.ip("public boolean isSupervised() { return " + isSupervised() + "; }").nl();
    sb.ip("public int nfeatures() { return "+_output.nfeatures()+"; }").nl();
    sb.ip("public int nclasses() { return "+_parms._k+"; }").nl();

    if (_output._nnums > 0) {
      JCodeGen.toStaticVar(sb, "NORMMUL", _output._normMul, "Standardization/Normalization scaling factor for numerical variables.");
      JCodeGen.toStaticVar(sb, "NORMSUB", _output._normSub, "Standardization/Normalization offset for numerical variables.");
    }
    JCodeGen.toStaticVar(sb, "CATOFFS", _output._catOffsets, "Categorical column offsets.");
    JCodeGen.toStaticVar(sb, "PERMUTE", _output._permutation, "Permutation index vector.");
    JCodeGen.toStaticVar(sb, "EIGVECS", _output._eigenvectors_raw, "Eigenvector matrix.");
    return sb;
  }

  @Override protected void toJavaPredictBody( final SB bodySb, final SB classCtxSb, final SB fileCtxSb) {
    SB model = new SB();
    bodySb.i().p("java.util.Arrays.fill(preds,0);").nl();
    final int cats = _output._ncats;
    final int nums = _output._nnums;
    bodySb.i().p("final int nstart = CATOFFS[CATOFFS.length-1];").nl();
    bodySb.i().p("for(int i = 0; i < ").p(_parms._k).p("; i++) {").nl();
    // Categorical columns
    bodySb.i(1).p("for(int j = 0; j < ").p(cats).p("; j++) {").nl();
    bodySb.i(2).p("double d = data[PERMUTE[j]];").nl();
    bodySb.i(2).p("int last = CATOFFS[j+1]-CATOFFS[j]-1;").nl();
    bodySb.i(2).p("int c = Double.isNaN(d) ? last : (int)d").p(_parms._use_all_factor_levels ? ";":"-1;").nl();
    bodySb.i(2).p("if(c < 0 || c > last) continue;").nl();
    bodySb.i(2).p("preds[i] += EIGVECS[CATOFFS[j]+c][i];").nl();
    bodySb.i(1).p("}").nl();

    // Numeric columns
    bodySb.i(1).p("for(int j = 0; j < ").p(nums).p("; j++) {").nl();
    bodySb.i(2).p("preds[i] += (data[PERMUTE[j" + (cats > 0 ? "+" + cats : "") + "]]-NORMSUB[j])*NORMMUL[j]*EIGVECS[j" + (cats > 0 ? "+ nstart" : "") +"][i];").nl();
    bodySb.i(1).p("}").nl();
    bodySb.i().p("}").nl();
    fileCtxSb.p(model);
  }
}
