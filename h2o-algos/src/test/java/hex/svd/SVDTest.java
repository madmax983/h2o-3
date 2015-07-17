package hex.svd;

import hex.DataInfo;
import hex.SplitFrame;
import hex.svd.SVDModel.SVDParameters;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.Ignore;
import water.DKV;
import water.Key;
import water.Scope;
import water.TestUtil;
import water.fvec.Frame;
import water.util.ArrayUtils;
import water.util.FrameUtils;
import water.util.Log;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

public class SVDTest extends TestUtil {
  public static final double TOLERANCE = 1e-6;

  @BeforeClass public static void setup() {
    stall_till_cloudsize(1);
  }

  @Test public void testArrestsSVD() throws InterruptedException, ExecutionException {
    // Expected right singular values and vectors
    double[] sdev_expected = new double[] {202.723056, 27.832264, 6.523048, 2.581365};
    double[] d_expected = new double[] {1419.06139510, 194.82584611, 45.66133763, 18.06955662};
    double[][] v_expected = ard(ard(-0.04239181,  0.01616262, -0.06588426,  0.99679535),
                                ard(-0.94395706,  0.32068580,  0.06655170, -0.04094568),
                                ard(-0.30842767, -0.93845891,  0.15496743,  0.01234261),
                                ard(-0.10963744, -0.12725666, -0.98347101, -0.06760284));
    SVDModel model = null;
    Frame train = null;
    try {
      train = parse_test_file(Key.make("arrests.hex"), "smalldata/pca_test/USArrests.csv");
      SVDModel.SVDParameters parms = new SVDModel.SVDParameters();
      parms._train = train._key;
      parms._nv = 4;
      parms._seed = 1234;
      parms._only_v = false;
      parms._transform = DataInfo.TransformType.NONE;

      SVD job = new SVD(parms);
      try {
        model = job.trainModel().get();
        TestUtil.checkEigvec(v_expected, model._output._v, TOLERANCE);
        Assert.assertArrayEquals(d_expected, model._output._d, TOLERANCE);
      } catch (Throwable t) {
        t.printStackTrace();
        throw new RuntimeException(t);
      } finally {
        job.remove();
      }
    } catch (Throwable t) {
      t.printStackTrace();
      throw new RuntimeException(t);
    } finally {
      if (train != null) train.delete();
      if (model != null) {
        if (model._parms._keep_u)
          model._output._u_key.get().delete();
        model.delete();
      }
    }
  }

  @Test public void testArrestsOnlyV() throws InterruptedException, ExecutionException {
    // Expected right singular vectors
    double[][] svec = ard(ard(-0.04239181,  0.01616262, -0.06588426,  0.99679535),
                          ard(-0.94395706,  0.32068580,  0.06655170, -0.04094568),
                          ard(-0.30842767, -0.93845891,  0.15496743,  0.01234261),
                          ard(-0.10963744, -0.12725666, -0.98347101, -0.06760284));
    SVDModel model = null;
    Frame train = null;
    try {
      train = parse_test_file(Key.make("arrests.hex"), "smalldata/pca_test/USArrests.csv");
      SVDModel.SVDParameters parms = new SVDModel.SVDParameters();
      parms._train = train._key;
      parms._nv = 4;
      parms._seed = 1234;
      parms._only_v = true;
      parms._transform = DataInfo.TransformType.NONE;

      SVD job = new SVD(parms);
      try {
        model = job.trainModel().get();
        TestUtil.checkEigvec(svec, model._output._v, TOLERANCE);
        assert model._output._d == null;
      } catch (Throwable t) {
        t.printStackTrace();
        throw new RuntimeException(t);
      } finally {
        job.remove();
      }
    } catch (Throwable t) {
      t.printStackTrace();
      throw new RuntimeException(t);
    } finally {
      if (train != null) train.delete();
      if (model != null) model.delete();
    }
  }

  @Test public void testArrestsScoring() throws InterruptedException, ExecutionException {
    double[] stddev = new double[] {202.7230564, 27.8322637, 6.5230482, 2.5813652};
    double[][] eigvec = ard(ard(-0.04239181, 0.01616262, -0.06588426, 0.99679535),
            ard(-0.94395706, 0.32068580, 0.06655170, -0.04094568),
            ard(-0.30842767, -0.93845891, 0.15496743, 0.01234261),
            ard(-0.10963744, -0.12725666, -0.98347101, -0.06760284));

    SVD job = null;
    SVDModel model = null;
    Frame train = null, score = null, scoreR = null;
    try {
      train = parse_test_file(Key.make("arrests.hex"), "smalldata/pca_test/USArrests.csv");
      SVDModel.SVDParameters parms = new SVDModel.SVDParameters();
      parms._train = train._key;
      parms._nv = 4;
      parms._transform = DataInfo.TransformType.NONE;
      parms._only_v = false;
      parms._keep_u = false;
      parms._transform = DataInfo.TransformType.NONE;

      try {
        job = new SVD(parms);
        model = job.trainModel().get();
        boolean[] flippedEig = TestUtil.checkEigvec(eigvec, model._output._v, TOLERANCE);

        score = model.score(train);
        scoreR = parse_test_file(Key.make("scoreR.hex"), "smalldata/pca_test/USArrests_PCAscore.csv");
        TestUtil.checkProjection(scoreR, score, TOLERANCE, flippedEig);    // Flipped cols must match those from eigenvectors
      } catch (Throwable t) {
        t.printStackTrace();
        throw new RuntimeException(t);
      } finally {
        if (job != null) job.remove();
      }
    } catch (Throwable t) {
      t.printStackTrace();
      throw new RuntimeException(t);
    } finally {
      if (train != null) train.delete();
      if (score != null) score.delete();
      if (scoreR != null) scoreR.delete();
      if (model != null) model.delete();
    }
  }

  @Test @Ignore public void testArrestsMissing() throws InterruptedException, ExecutionException {
    SVDModel model = null;
    SVDParameters parms = null;
    Frame train = null;
    long seed = 1234;

    for (double missing_fraction : new double[]{0, 0.1, 0.25, 0.5, 0.75, 0.9}) {
      try {
        Scope.enter();
        train = parse_test_file(Key.make("arrests.hex"), "smalldata/pca_test/USArrests.csv");

        // Add missing values to the training data
        if (missing_fraction > 0) {
          Frame frtmp = new Frame(Key.make(), train.names(), train.vecs());
          DKV.put(frtmp._key, frtmp); // Need to put the frame (to be modified) into DKV for MissingInserter to pick up
          FrameUtils.MissingInserter j = new FrameUtils.MissingInserter(frtmp._key, seed, missing_fraction);
          j.execImpl();
          j.get(); // MissingInserter is non-blocking, must block here explicitly
          DKV.remove(frtmp._key); // Delete the frame header (not the data)
        }

        parms = new SVDParameters();
        parms._train = train._key;
        parms._nv = train.numCols();
        parms._transform = DataInfo.TransformType.STANDARDIZE;
        parms._max_iterations = 1000;
        parms._seed = seed;

        SVD job = new SVD(parms);
        try {
          model = job.trainModel().get();
          Log.info(100 * missing_fraction + "% missing values: Singular values = " + Arrays.toString(model._output._d));
        } catch (Throwable t) {
          t.printStackTrace();
          throw new RuntimeException(t);
        } finally {
          job.remove();
        }
        Scope.exit();
      } catch(Throwable t) {
        t.printStackTrace();
        throw new RuntimeException(t);
      } finally {
        if (train != null) train.delete();
        if (model != null) {
          model._output._u_key.get().delete();
          model.delete();
        }
      }
    }
  }

  @Test public void testIrisSVDScore() throws InterruptedException, ExecutionException {
    // Expected right singular values and vectors
    double[] d_expected = new double[] {96.2090445, 19.0425654, 7.2250378, 3.1636131, 1.8816739, 1.1451307, 0.5820806};
    double[][] v_expected = ard(ard(-0.03169051, -0.32305860,  0.185100382, -0.12336685, -0.14867156,  0.75932119, -0.496462912),
                                ard(-0.04289677,  0.04037565, -0.780961964,  0.19727933,  0.07251338, -0.12216945, -0.572298338),
                                ard(-0.05019689,  0.16836717,  0.551432201, -0.07122329,  0.08454116, -0.48327010, -0.647522462),
                                ard(-0.74915107, -0.26629420, -0.101102186, -0.48920057,  0.32458460, -0.09176909,  0.067412858),
                                ard(-0.37877011, -0.50636060,  0.142219195,  0.69081642, -0.26312992, -0.17811871,  0.041411296),
                                ard(-0.51177078,  0.65945159, -0.005079934,  0.04881900, -0.52128288,  0.17038367,  0.006223427),
                                ard(-0.16742875,  0.32166036,  0.145893901,  0.47102115,  0.72052968,  0.32523458,  0.020389463));
    SVDModel model = null;
    Frame train = null, score = null;
    try {
      train = parse_test_file(Key.make("iris.hex"), "smalldata/iris/iris_wheader.csv");
      SVDModel.SVDParameters parms = new SVDModel.SVDParameters();
      parms._train = train._key;
      parms._nv = 7;
      parms._use_all_factor_levels = true;
      parms._only_v = false;
      parms._transform = DataInfo.TransformType.NONE;

      SVD job = new SVD(parms);
      try {
        model = job.trainModel().get();
        TestUtil.checkEigvec(v_expected, model._output._v, TOLERANCE);
        Assert.assertArrayEquals(d_expected, model._output._d, TOLERANCE);
        score = model.score(train);
      } catch (Throwable t) {
        t.printStackTrace();
        throw new RuntimeException(t);
      } finally {
        job.remove();
      }
    } catch (Throwable t) {
      t.printStackTrace();
      throw new RuntimeException(t);
    } finally {
      if (train != null) train.delete();
      if (score != null) score.delete();
      if (model != null) {
        if (model._parms._keep_u)
          model._output._u_key.get().delete();
        model.delete();
      }
    }
  }

  @Test public void testIrisSplitScoring() throws InterruptedException, ExecutionException {
    SVD job = null;
    SVDModel model = null;
    Frame fr = null, fr2= null;
    Frame tr = null, te= null;

    try {
      fr = parse_test_file("smalldata/iris/iris_wheader.csv");
      SplitFrame sf = new SplitFrame(Key.make());
      sf.dataset = fr;
      sf.ratios = new double[] { 0.5, 0.5 };
      sf.destination_frames = new Key[] { Key.make("train.hex"), Key.make("test.hex")};

      // Invoke the job
      sf.exec().get();
      Key[] ksplits = sf.destination_frames;
      tr = DKV.get(ksplits[0]).get();
      te = DKV.get(ksplits[1]).get();

      SVDModel.SVDParameters parms = new SVDModel.SVDParameters();
      parms._train = ksplits[0];
      parms._valid = ksplits[1];
      parms._nv = 4;
      parms._max_iterations = 1000;

      try {
        job = new SVD(parms);
        model = job.trainModel().get();
      } finally {
        if (job != null) job.remove();
      }

      // Done building model; produce a score column with cluster choices
      fr2 = model.score(te);
      Assert.assertTrue(model.testJavaScoring(te, fr2, 1e-5));
    } catch (Throwable t) {
      t.printStackTrace();
      throw new RuntimeException(t);
    } finally {
      if( fr  != null ) fr.delete();
      if( fr2 != null ) fr2.delete();
      if( tr  != null ) tr .delete();
      if( te  != null ) te .delete();
      if (model != null) {
        if (model._parms._keep_u)
          model._output._u_key.get().delete();
        model.delete();
      }
    }
  }

  @Test public void testIVVSum() {
    double[][] res = ard(ard(1, 2, 3), ard(2, 5, 6), ard(3, 6, 9));
    double[] v = new double[] {7, 8, 9};
    double[][] xvv = ard(ard(-48, -54, -60), ard(-54, -59, -66), ard(-60, -66, -72));

    SVD.updateIVVSum(res, v);
    Assert.assertArrayEquals(xvv, res);
  }
}
