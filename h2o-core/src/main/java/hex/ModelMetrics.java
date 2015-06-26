package hex;

import water.*;
import water.fvec.Frame;
import water.util.TwoDimTable;

import java.util.Arrays;
import java.util.Comparator;

/** Container to hold the metric for a model as scored on a specific frame.
 *
 *  The MetricBuilder class is used in a hot inner-loop of a Big Data pass, and
 *  when given a class-distribution, can be used to compute CM's, and AUC's "on
 *  the fly" during ModelBuilding - or after-the-fact with a Model and a new
 *  Frame to be scored.
 */
public class ModelMetrics extends Keyed<ModelMetrics> {
  public String _description;
  final Key _modelKey;
  final Key _frameKey;
  final ModelCategory _model_category;
  final long _model_checksum;
  final long _frame_checksum;
  transient Model _model;
  transient Frame _frame;
  public final long _scoring_time;
//  public long _duration_in_ms;

  public final double _MSE;     // Mean Squared Error (Every model is assumed to have this, otherwise leave at NaN)

  public ModelMetrics(Model model, Frame frame, double MSE, String desc) {
    super(buildKey(model, frame));
    _description = desc;
    _modelKey = model._key;
    _frameKey = frame._key;
    _model_category = model._output.getModelCategory();
    _model = model;
    _frame = frame;
    _model_checksum = model.checksum();
    _frame_checksum = frame.checksum();
    _MSE = MSE;
    _scoring_time = System.currentTimeMillis();
    DKV.put(this);
  }

  public Model model() { return _model==null ? (_model=DKV.getGet(_modelKey)) : _model; }
  public Frame frame() { return _frame==null ? (_frame=DKV.getGet(_frameKey)) : _frame; }

  public double mse() { return _MSE; }
  public ConfusionMatrix cm() { return null; }
  public float[] hr() { return null; }
  public AUC2 auc() { return null; }

  public static TwoDimTable calcVarImp(VarImp vi) {
    if (vi == null) return null;
    double[] dbl_rel_imp = new double[vi._varimp.length];
    for (int i=0; i<dbl_rel_imp.length; ++i) {
      dbl_rel_imp[i] = vi._varimp[i];
    }
    return calcVarImp(dbl_rel_imp, vi._names);
  }
  public static TwoDimTable calcVarImp(final float[] rel_imp, String[] coef_names) {
    double[] dbl_rel_imp = new double[rel_imp.length];
    for (int i=0; i<dbl_rel_imp.length; ++i) {
      dbl_rel_imp[i] = rel_imp[i];
    }
    return calcVarImp(dbl_rel_imp, coef_names);
  }
  public static TwoDimTable calcVarImp(final double[] rel_imp, String[] coef_names) {
    return calcVarImp(rel_imp, coef_names, "Variable Importances", new String[]{"Relative Importance", "Scaled Importance", "Percentage"});
  }
  public static TwoDimTable calcVarImp(final double[] rel_imp, String[] coef_names, String table_header, String[] col_headers) {
    if(rel_imp == null) return null;
    if(coef_names == null) {
      coef_names = new String[rel_imp.length];
      for(int i = 0; i < coef_names.length; i++)
        coef_names[i] = "C" + String.valueOf(i+1);
    }

    // Sort in descending order by relative importance
    Integer[] sorted_idx = new Integer[rel_imp.length];
    for(int i = 0; i < sorted_idx.length; i++) sorted_idx[i] = i;
    Arrays.sort(sorted_idx, new Comparator<Integer>() {
      public int compare(Integer idx1, Integer idx2) {
        return Double.compare(-rel_imp[idx1], -rel_imp[idx2]);
      }
    });

    double total = 0;
    double max = rel_imp[sorted_idx[0]];
    String[] sorted_names = new String[rel_imp.length];
    double[][] sorted_imp = new double[rel_imp.length][3];

    // First pass to sum up relative importance measures
    int j = 0;
    for(int i : sorted_idx) {
      total += rel_imp[i];
      sorted_names[j] = coef_names[i];
      sorted_imp[j][0] = rel_imp[i];         // Relative importance
      sorted_imp[j++][1] = rel_imp[i] / max;   // Scaled importance
    }
    // Second pass to calculate percentages
    j = 0;
    for(int i : sorted_idx)
      sorted_imp[j++][2] = rel_imp[i] / total; // Percentage

    String [] col_types = new String[3];
    String [] col_formats = new String[3];
    Arrays.fill(col_types, "double");
    Arrays.fill(col_formats, "%5f");
    return new TwoDimTable(table_header, null, sorted_names, col_headers, col_types, col_formats, "Variable",
            new String[rel_imp.length][], sorted_imp);
  }

  private static Key<ModelMetrics> buildKey(Key model_key, long model_checksum, Key frame_key, long frame_checksum) {
    return Key.make("modelmetrics_" + model_key + "@" + model_checksum + "_on_" + frame_key + "@" + frame_checksum);
  }

  public static Key<ModelMetrics> buildKey(Model model, Frame frame) {
    return frame==null ? null : buildKey(model._key, model.checksum(), frame._key, frame.checksum());
  }

  public boolean isForModel(Model m) { return _model_checksum == m.checksum(); }
  public boolean isForFrame(Frame f) { return _frame_checksum == f.checksum(); }

  public static ModelMetrics getFromDKV(Model model, Frame frame) {
    Value v = DKV.get(buildKey(model, frame));
    return null == v ? null : (ModelMetrics)v.get();
  }

  @Override protected long checksum_impl() { return _frame_checksum * 13 + _model_checksum * 17; }

  /** Class used to compute AUCs, CMs & HRs "on the fly" during other passes
   *  over Big Data.  This class is intended to be embedded in other MRTask
   *  objects.  The {@code perRow} method is called once-per-scored-row, and
   *  the {@code reduce} method called once per MRTask.reduce, and the {@code
   *  <init>} called once per MRTask.map.
   */
  public static abstract class MetricBuilder<T extends MetricBuilder<T>> extends Iced {
    transient public double[] _work;
    public double _sumsqe;      // Sum-squared-error
    public long _count;
    public double _wcount;
    public double _wY; // (Weighted) sum of the response
    public double _wYY; // (Weighted) sum of the squared response

    public  double weightedSigma() {
      return _wcount <= 1 ? 0 : Math.sqrt(1./(_wcount-1.)*_wYY-(1./(_wcount-1.)/_wcount*_wY*_wY));
    }
    abstract public double[] perRow(double ds[], float yact[], Model m);
    public double[] perRow(double ds[], float yact[],double weight, double offset,  Model m) {
      assert(weight==1 && offset == 0);
      return perRow(ds, yact, m);
    }
    public void reduce( T mb ) {
      _sumsqe += mb._sumsqe;
      _count += mb._count;
      _wcount += mb._wcount;
      _wY += mb._wY;
      _wYY += mb._wYY;
    }

    public void postGlobal() {}
    // Having computed a MetricBuilder, this method fills in a ModelMetrics
    public abstract ModelMetrics makeModelMetrics( Model m, Frame f);
  }
}
