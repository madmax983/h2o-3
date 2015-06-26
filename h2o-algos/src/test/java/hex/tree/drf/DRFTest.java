package hex.tree.drf;


import hex.ModelMetricsBinomial;
import org.junit.*;
import static org.junit.Assert.assertEquals;
import water.*;
import water.exceptions.H2OModelBuilderIllegalArgumentException;
import water.fvec.Frame;
import water.fvec.RebalanceDataSet;
import water.fvec.Vec;
import water.util.Log;

import java.util.Arrays;
import java.util.Random;

public class DRFTest extends TestUtil {
  @BeforeClass public static void stall() { stall_till_cloudsize(1); }

  abstract static class PrepData { abstract int prep(Frame fr); }

  static String[] s(String...arr)  { return arr; }
  static long[]   a(long ...arr)   { return arr; }
  static long[][] a(long[] ...arr) { return arr; }

  @Test public void testClassIris1() throws Throwable {

    // iris ntree=1
    // the DRF should  use only subset of rows since it is using oob validation
    basicDRFTestOOBE_Classification(
            "./smalldata/iris/iris.csv", "iris.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                return fr.numCols() - 1;
              }
            },
            1,
            20,
            1,
            20,
            ard(ard(25, 0, 0),
                    ard(0, 17, 1),
                    ard(2, 1, 15)),
            s("Iris-setosa", "Iris-versicolor", "Iris-virginica"));

  }

  @Test public void testClassIris5() throws Throwable {
    // iris ntree=50
    basicDRFTestOOBE_Classification(
            "./smalldata/iris/iris.csv", "iris5.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                return fr.numCols() - 1;
              }
            },
            5,
            20,
            1,
            20,
            ard(ard(41, 0, 0),
                    ard(1, 39, 2),
                    ard(1, 3, 41)),
            s("Iris-setosa", "Iris-versicolor", "Iris-virginica"));
  }

  @Test public void testClassCars1() throws Throwable {
    // cars ntree=1
    basicDRFTestOOBE_Classification(
            "./smalldata/junit/cars.csv", "cars.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                fr.remove("name").remove();
                return fr.find("cylinders");
              }
            },
            1,
            20,
            1,
            20,
            ard(ard(0, 0, 0, 0, 0),
                    ard(3, 65, 0, 1, 0),
                    ard(0, 1, 0, 0, 0),
                    ard(0, 0, 1, 30, 0),
                    ard(0, 0, 0, 1, 39)),
            s("3", "4", "5", "6", "8"));
  }

  @Test public void testClassCars5() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/junit/cars.csv", "cars5.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                fr.remove("name").remove();
                return fr.find("cylinders");
              }
            },
            5,
            20,
            1,
            20,
            ard(ard(3, 0, 0, 0, 0),
                    ard(2, 179, 1, 2, 0),
                    ard(0, 1, 1, 0, 0),
                    ard(0, 3, 2, 68, 1),
                    ard(0, 0, 0, 3, 87)),
            s("3", "4", "5", "6", "8"));
  }

  @Test public void testConstantCols() throws Throwable {
    try {
      basicDRFTestOOBE_Classification(
              "./smalldata/poker/poker100", "poker.hex",
              new PrepData() {
                @Override
                int prep(Frame fr) {
                  for (int i = 0; i < 7; i++) {
                    fr.remove(3).remove();
                  }
                  return 3;
                }
              },
              1,
              20,
              1,
              20,
              null,
              null);
      Assert.fail();
    } catch( H2OModelBuilderIllegalArgumentException iae ) {
    /*pass*/
    }
  }

  @Ignore @Test public void testBadData() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/junit/drf_infinities.csv", "infinitys.hex",
            new PrepData() { @Override int prep(Frame fr) { return fr.find("DateofBirth"); } },
            1,
            20,
            1,
            20,
            ard(ard(6, 0),
                    ard(9, 1)),
            s("0", "1"));
  }

  //@Test
  public void testCreditSample1() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/kaggle/creditsample-training.csv.gz", "credit.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                fr.remove("MonthlyIncome").remove();
                return fr.find("SeriousDlqin2yrs");
              }
            },
            1,
            20,
            1,
            20,
            ard(ard(46294, 202),
                    ard(3187, 107)),
            s("0", "1"));

  }

  @Test public void testCreditProstate1() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/logreg/prostate.csv", "prostate.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                fr.remove("ID").remove();
                return fr.find("CAPSULE");
              }
            },
            1,
            20,
            1,
            20,
            ard(ard(0, 81),
                    ard(0, 53)),
            s("0", "1"));

  }

  @Test public void testCreditProstateRegression1() throws Throwable {
    basicDRFTestOOBE_Regression(
            "./smalldata/logreg/prostate.csv", "prostateRegression.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                fr.remove("ID").remove();
                return fr.find("AGE");
              }
            },
            1,
            20,
            1,
            10,
            84.83960821204235
    );

  }

  @Test public void testCreditProstateRegression5() throws Throwable {
    basicDRFTestOOBE_Regression(
            "./smalldata/logreg/prostate.csv", "prostateRegression5.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                fr.remove("ID").remove();
                return fr.find("AGE");
              }
            },
            5,
            20,
            1,
            10,
            62.34506879389341
    );

  }

  @Test public void testCreditProstateRegression50() throws Throwable {
    basicDRFTestOOBE_Regression(
            "./smalldata/logreg/prostate.csv", "prostateRegression50.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                fr.remove("ID").remove();
                return fr.find("AGE");
              }
            },
            50,
            20,
            1,
            10,
            48.16452593965962
    );

  }
  @Test public void testCzechboard() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/gbm_test/czechboard_300x300.csv", "czechboard_300x300.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                Vec resp = fr.remove("C2");
                fr.add("C2", resp.toEnum());
                resp.remove();
                return fr.find("C3");
              }
            },
            50,
            20,
            1,
            20,
            ard(ard(0, 45000),
                    ard(0, 45000)),
            s("0", "1"));
  }

  @Test public void testProstate() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/prostate/prostate.csv.zip", "prostate2.zip.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                String[] names = fr.names().clone();
                Vec[] en = fr.remove(new int[]{1,4,5,8});
                fr.add(names[1], en[0].toEnum()); //CAPSULE
                fr.add(names[4], en[1].toEnum()); //DPROS
                fr.add(names[5], en[2].toEnum()); //DCAPS
                fr.add(names[8], en[3].toEnum()); //GLEASON
                for (Vec v : en) v.remove();
                fr.remove(0).remove(); //drop ID
                return 4; //CAPSULE
              }
            },
            4, //ntrees
            2, //bins
            1, //min_rows
            1, //max_depth
            null,
            s("0", "1"));
  }

  @Test public void testAlphabet() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/gbm_test/alphabet_cattest.csv", "alphabetClassification.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                return fr.find("y");
              }
            },
            1,
            20,
            1,
            20,
            ard(ard(664, 0),
                    ard(0, 702)),
            s("0", "1"));
  }
  @Test public void testAlphabetRegression() throws Throwable {
    basicDRFTestOOBE_Regression(
            "./smalldata/gbm_test/alphabet_cattest.csv", "alphabetRegression.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                return fr.find("y");
              }
            },
            1,
            20,
            1,
            10,
            0.0);
  }

  @Ignore  //1-vs-5 node discrepancy (parsing into different number of chunks?)
  @Test public void testAirlines() throws Throwable {
    basicDRFTestOOBE_Classification(
            "./smalldata/airlines/allyears2k_headers.zip", "airlines.hex",
            new PrepData() {
              @Override
              int prep(Frame fr) {
                for (String s : new String[]{
                        "DepTime", "ArrTime", "ActualElapsedTime",
                        "AirTime", "ArrDelay", "DepDelay", "Cancelled",
                        "CancellationCode", "CarrierDelay", "WeatherDelay",
                        "NASDelay", "SecurityDelay", "LateAircraftDelay", "IsArrDelayed"
                }) {
                  fr.remove(s).remove();
                }
                return fr.find("IsDepDelayed");
              }
            },
            7,
            20, 1, 20, ard(ard(7958, 11707), //1-node
                    ard(2709, 19024)),
//          a(a(7841, 11822), //5-node
//            a(2666, 19053)),
            s("NO", "YES"));
  }



  // Put response as the last vector in the frame and return possible frames to clean up later
  // Also fill DRF.
  static Vec unifyFrame(DRFModel.DRFParameters drf, Frame fr, PrepData prep, boolean classification) {
    int idx = prep.prep(fr);
    if( idx < 0 ) { idx = ~idx; }
    String rname = fr._names[idx];
    drf._response_column = fr.names()[idx];

    Vec resp = fr.vecs()[idx];
    Vec ret = null;
    if (classification) {
      ret = fr.remove(idx);
      fr.add(rname,resp.toEnum());
    } else {
      fr.remove(idx);
      fr.add(rname,resp);
    }
    return ret;
  }

  public void basicDRFTestOOBE_Classification(String fnametrain, String hexnametrain, PrepData prep, int ntree, int nbins, int min_rows, int max_depth, double[][] expCM, String[] expRespDom) throws Throwable {
    basicDRF(fnametrain, hexnametrain, null, prep, ntree, max_depth, nbins, true, min_rows, expCM, -1, expRespDom);
  }
  public void basicDRFTestOOBE_Regression(String fnametrain, String hexnametrain, PrepData prep, int ntree, int nbins, int min_rows, int max_depth, double expMSE) throws Throwable {
    basicDRF(fnametrain, hexnametrain, null, prep, ntree, max_depth, nbins, false, min_rows, null, expMSE, null);
  }

  public void basicDRF(String fnametrain, String hexnametrain, String fnametest, PrepData prep, int ntree, int max_depth, int nbins, boolean classification, int min_rows, double[][] expCM, double expMSE, String[] expRespDom) throws Throwable {
    Scope.enter();
    DRFModel.DRFParameters drf = new DRFModel.DRFParameters();
    Frame frTest = null, pred = null;
    Frame frTrain = null;
    Frame test = null, res = null;
    DRFModel model = null;
    try {
      frTrain = parse_test_file(fnametrain);
      Vec removeme = unifyFrame(drf, frTrain, prep, classification);
      if (removeme != null) Scope.track(removeme._key);
      DKV.put(frTrain._key, frTrain);
      // Configure DRF
      drf._train = frTrain._key;
      drf._response_column = ((Frame)DKV.getGet(drf._train)).lastVecName();
      drf._ntrees = ntree;
      drf._max_depth = max_depth;
      drf._min_rows = min_rows;
      drf._binomial_double_trees = new Random().nextBoolean();
      drf._nbins = nbins;
      drf._nbins_cats = nbins;
      drf._mtries = -1;
      drf._sample_rate = 0.66667f;   // Simulated sampling with replacement
      drf._seed = (1L<<32)|2;
      drf._model_id = Key.make("DRF_model_4_" + hexnametrain);

      // Invoke DRF and block till the end
      DRF job = null;
      try {
        job = new DRF(drf);
        // Get the model
        model = job.trainModel().get();
        Log.info(model._output);
      } finally {
        if (job != null) job.remove();
      }
      Assert.assertTrue(job._state == water.Job.JobState.DONE); //HEX-1817

      hex.ModelMetrics mm;
      if (fnametest != null) {
        frTest = parse_test_file(fnametest);
        pred = model.score(frTest);
        mm = hex.ModelMetrics.getFromDKV(model, frTest);
        // Check test set CM
      } else {
        mm = hex.ModelMetrics.getFromDKV(model, frTrain);
      }
      Assert.assertEquals("Number of trees differs!", ntree, model._output._ntrees);

      test = parse_test_file(fnametrain);
      res = model.score(test);

      // Build a POJO, validate same results
      Assert.assertTrue(model.testJavaScoring(test,res,1e-15));

      if (classification && expCM != null) {
        Assert.assertTrue("Expected: " + Arrays.deepToString(expCM) + ", Got: " + Arrays.deepToString(mm.cm()._cm),
                Arrays.deepEquals(mm.cm()._cm, expCM));

        String[] cmDom = model._output._domains[model._output._domains.length - 1];
        Assert.assertArrayEquals("CM domain differs!", expRespDom, cmDom);
        Log.info("\nOOB Training CM:\n" + mm.cm().toASCII());
        Log.info("\nTraining CM:\n" + hex.ModelMetrics.getFromDKV(model, test).cm().toASCII());
      } else if (!classification) {
        Assert.assertTrue("Expected: " + expMSE + ", Got: " + mm.mse(), expMSE == mm.mse());
        Log.info("\nOOB Training MSE: " + mm.mse());
        Log.info("\nTraining MSE: " + hex.ModelMetrics.getFromDKV(model, test).mse());
      }

      hex.ModelMetrics.getFromDKV(model, test);

    } finally {
      if (frTrain!=null) frTrain.remove();
      if (frTest!=null) frTest.remove();
      if( model != null ) model.delete(); // Remove the model
      if( pred != null ) pred.delete();
      if( test != null ) test.delete();
      if( res != null ) res.delete();
      Scope.exit();
    }
  }

  // HEXDEV-194 Check reproducibility for the same # of chunks (i.e., same # of nodes) and same parameters
  @Test public void testReproducibility() {
    Frame tfr=null;
    final int N = 5;
    double[] mses = new double[N];

    Scope.enter();
    try {
      // Load data, hack frames
      tfr = parse_test_file("smalldata/covtype/covtype.20k.data");

      // rebalance to 256 chunks
      Key dest = Key.make("df.rebalanced.hex");
      RebalanceDataSet rb = new RebalanceDataSet(tfr, dest, 256);
      H2O.submitTask(rb);
      rb.join();
      tfr.delete();
      tfr = DKV.get(dest).get();
//      Scope.track(tfr.replace(54, tfr.vecs()[54].toEnum())._key);
//      DKV.put(tfr);

      for (int i=0; i<N; ++i) {
        DRFModel.DRFParameters parms = new DRFModel.DRFParameters();
        parms._train = tfr._key;
        parms._response_column = "C55";
        parms._nbins = 1000;
        parms._ntrees = 1;
        parms._max_depth = 8;
        parms._mtries = -1;
        parms._min_rows = 10;
        parms._seed = 1234;

        // Build a first model; all remaining models should be equal
        DRF job = new DRF(parms);
        DRFModel drf = job.trainModel().get();
        assertEquals(drf._output._ntrees, parms._ntrees);

        mses[i] = drf._output._scored_train[drf._output._scored_train.length-1]._mse;
        job.remove();
        drf.delete();
      }
    } finally{
      if (tfr != null) tfr.remove();
    }
    Scope.exit();
    for (int i=0; i<mses.length; ++i) {
      Log.info("trial: " + i + " -> MSE: " + mses[i]);
    }
    for(double mse : mses)
      assertEquals(mse, mses[0], 1e-15);
  }

  // PUBDEV-557 Test dependency on # nodes (for small number of bins, but fixed number of chunks)
  @Test public void testReprodubilityAirline() {
    Frame tfr=null;
    final int N = 1;
    double[] mses = new double[N];

    Scope.enter();
    try {
      // Load data, hack frames
      tfr = parse_test_file("./smalldata/airlines/allyears2k_headers.zip");

      // rebalance to fixed number of chunks
      Key dest = Key.make("df.rebalanced.hex");
      RebalanceDataSet rb = new RebalanceDataSet(tfr, dest, 256);
      H2O.submitTask(rb);
      rb.join();
      tfr.delete();
      tfr = DKV.get(dest).get();
//      Scope.track(tfr.replace(54, tfr.vecs()[54].toEnum())._key);
//      DKV.put(tfr);
      for (String s : new String[]{
          "DepTime", "ArrTime", "ActualElapsedTime",
          "AirTime", "ArrDelay", "DepDelay", "Cancelled",
          "CancellationCode", "CarrierDelay", "WeatherDelay",
          "NASDelay", "SecurityDelay", "LateAircraftDelay", "IsArrDelayed"
      }) {
        tfr.remove(s).remove();
      }
      DKV.put(tfr);
      for (int i=0; i<N; ++i) {
        DRFModel.DRFParameters parms = new DRFModel.DRFParameters();
        parms._train = tfr._key;
        parms._response_column = "IsDepDelayed";
        parms._nbins = 10;
        parms._nbins_cats = 1024;
        parms._ntrees = 7;
        parms._max_depth = 10;
        parms._binomial_double_trees = true;
        parms._mtries = -1;
        parms._min_rows = 1;
        parms._sample_rate = 0.632f;   // Simulated sampling with replacement
        parms._balance_classes = true;
        parms._seed = (1L<<32)|2;

        // Build a first model; all remaining models should be equal
        DRF job = new DRF(parms);
        DRFModel drf = job.trainModel().get();
        assertEquals(drf._output._ntrees, parms._ntrees);

        mses[i] = drf._output._scored_train[drf._output._scored_train.length-1]._mse;
        job.remove();
        drf.delete();
      }
    } finally{
      if (tfr != null) tfr.remove();
    }
    Scope.exit();
    for (int i=0; i<mses.length; ++i) {
      Log.info("trial: " + i + " -> MSE: " + mses[i]);
    }
    for (int i=0; i<mses.length; ++i) {
      assertEquals(0.20841945889333927, mses[i], 1e-4); //check for the same result on 1 nodes and 5 nodes
    }
  }

  // HEXDEV-319
  @Ignore
  @Test public void testAirline() {
    Frame tfr=null;
    Frame test=null;

    Scope.enter();
    try {
      // Load data, hack frames
      tfr = parse_test_file(Key.make("air.hex"), "/users/arno/sz_bench_data/train-1m.csv");
      test = parse_test_file(Key.make("airt.hex"), "/users/arno/sz_bench_data/test.csv");
      for (int i : new int[]{0,1,2}) {
        tfr.vecs()[i] = tfr.vecs()[i].toEnum();
        test.vecs()[i] = test.vecs()[i].toEnum();
      }

      DRFModel.DRFParameters parms = new DRFModel.DRFParameters();
      parms._train = tfr._key;
      parms._valid = test._key;
//      parms._ignored_columns = new String[]{"UniqueCarrier","Origin","Dest"};
//      parms._ignored_columns = new String[]{"UniqueCarrier","Origin"};
//      parms._ignored_columns = new String[]{"Month","DayofMonth","DayOfWeek","DepTime","UniqueCarrier","Origin","Distance"};
      parms._response_column = "dep_delayed_15min";
      parms._nbins = 20;
      parms._nbins_cats = 20;
      parms._binomial_double_trees = true;
      parms._ntrees = 100;
      parms._max_depth = 20;
      parms._mtries = -1;
      parms._sample_rate = 0.632f;
      parms._min_rows = 10;
      parms._seed = 12;

      // Build a first model; all remaining models should be equal
      DRF job = new DRF(parms);
      DRFModel drf = job.trainModel().get();
      Log.info("Test set AUC: " + drf._output._validation_metrics.auc()._auc);

      job.remove();
      drf.delete();
    } finally{
      if (tfr != null) tfr.remove();
      if (test != null) test.remove();
    }
    Scope.exit();
  }

  @Test
  public void testNoRowWeights() {
    Frame tfr = null, vfr = null;
    DRFModel drf = null;

    Scope.enter();
    try {
      tfr = parse_test_file("smalldata/junit/no_weights.csv");
      DKV.put(tfr);
      DRFModel.DRFParameters parms = new DRFModel.DRFParameters();
      parms._train = tfr._key;
      parms._response_column = "response";
      parms._seed = 234;
      parms._min_rows = 1;
      parms._max_depth = 2;
      parms._ntrees = 3;

      // Build a first model; all remaining models should be equal
      DRF job = new DRF(parms);
      drf = job.trainModel().get();

      drf.score(parms.train());
      hex.ModelMetricsBinomial mm = hex.ModelMetricsBinomial.getFromDKV(drf, parms.train());
      assertEquals(1.0, mm.auc()._auc, 1e-8);

      double mse = drf._output._training_metrics.mse();
      assertEquals(0.07692307692307693, mse, 1e-8);

      double r2 = ((ModelMetricsBinomial)drf._output._training_metrics).r2();
      assertEquals(0.7142857142857142, r2, 1e-6);

      double ll = ((ModelMetricsBinomial)drf._output._training_metrics)._logloss;
      assertEquals(2.656828953454668, ll, 1e-6);

      job.remove();
    } finally {
      if (tfr != null) tfr.remove();
      if (vfr != null) vfr.remove();
      if (drf != null) drf.remove();
      Scope.exit();
    }
  }

  @Test
  public void testRowWeightsOne() {
    Frame tfr = null, vfr = null;
    DRFModel drf = null;

    Scope.enter();
    try {
      tfr = parse_test_file("smalldata/junit/weights_all_ones.csv");
      DKV.put(tfr);
      DRFModel.DRFParameters parms = new DRFModel.DRFParameters();
      parms._train = tfr._key;
      parms._response_column = "response";
      parms._weights_column = "weight";
      parms._seed = 234;
      parms._min_rows = 1;
      parms._max_depth = 2;
      parms._ntrees = 3;

      // Build a first model; all remaining models should be equal
      DRF job = new DRF(parms);
      drf = job.trainModel().get();

      drf.score(parms.train());
      hex.ModelMetricsBinomial mm = hex.ModelMetricsBinomial.getFromDKV(drf, parms.train());
      assertEquals(1.0, mm.auc()._auc, 1e-8);

      double mse = drf._output._training_metrics.mse();
      assertEquals(0.07692307692307693, mse, 1e-8); //Note: better results than non-shuffled

      double r2 = ((ModelMetricsBinomial)drf._output._training_metrics).r2();
      assertEquals(0.7142857142857142, r2, 1e-6);

      double ll = ((ModelMetricsBinomial)drf._output._training_metrics)._logloss;
      assertEquals(2.656828953454668, ll, 1e-6);

      job.remove();
    } finally {
      if (tfr != null) tfr.remove();
      if (vfr != null) vfr.remove();
      if (drf != null) drf.delete();
      Scope.exit();
    }
  }

  @Test
  public void testNoRowWeightsShuffled() {
    Frame tfr = null, vfr = null;
    DRFModel drf = null;

    Scope.enter();
    try {
      tfr = parse_test_file("smalldata/junit/no_weights_shuffled.csv");
      DKV.put(tfr);
      DRFModel.DRFParameters parms = new DRFModel.DRFParameters();
      parms._train = tfr._key;
      parms._response_column = "response";
      parms._seed = 234;
      parms._min_rows = 1;
      parms._max_depth = 2;
      parms._ntrees = 3;

      // Build a first model; all remaining models should be equal
      DRF job = new DRF(parms);
      drf = job.trainModel().get();

      drf.score(parms.train());
      hex.ModelMetricsBinomial mm = hex.ModelMetricsBinomial.getFromDKV(drf, parms.train());
      assertEquals(1.0, mm.auc()._auc, 1e-8);

      double mse = drf._output._training_metrics.mse();
      assertEquals(0.11538629999502548, mse, 1e-8); //different rows are sampled -> results differ from unshuffled data

      double r2 = ((ModelMetricsBinomial)drf._output._training_metrics).r2();
      assertEquals(0.5499934300194007, r2, 1e-6);

      double ll = ((ModelMetricsBinomial)drf._output._training_metrics)._logloss;
      assertEquals(0.31942928561508804, ll, 1e-6);

      job.remove();
    } finally {
      if (tfr != null) tfr.remove();
      if (vfr != null) vfr.remove();
      if (drf != null) drf.delete();
      Scope.exit();
    }
  }

  @Test
  public void testRowWeights() {
    Frame tfr = null, vfr = null;
    DRFModel drf = null;

    Scope.enter();
    try {
      tfr = parse_test_file("smalldata/junit/weights.csv");
      DKV.put(tfr);
      DRFModel.DRFParameters parms = new DRFModel.DRFParameters();
      parms._train = tfr._key;
      parms._response_column = "response";
      parms._weights_column = "weight";
      parms._seed = 234;
      parms._min_rows = 1;
      parms._max_depth = 2;
      parms._ntrees = 3;

      // Build a first model; all remaining models should be equal
      DRF job = new DRF(parms);
      drf = job.trainModel().get();

      drf.score(parms.train());
      hex.ModelMetricsBinomial mm = hex.ModelMetricsBinomial.getFromDKV(drf, parms.train());
      assertEquals(1.0, mm.auc()._auc, 1e-8);

      double mse = drf._output._training_metrics.mse();
      assertEquals(0.09090909090909091, mse, 1e-8); //different than above - different row sampling

      double r2 = ((ModelMetricsBinomial)drf._output._training_metrics).r2();
      assertEquals(0.6666666666666666, r2, 1e-6);

      double ll = ((ModelMetricsBinomial)drf._output._training_metrics)._logloss;
      assertEquals(3.1398887631736985, ll, 1e-6);

      job.remove();
    } finally {
      if (tfr != null) tfr.remove();
      if (vfr != null) vfr.remove();
      if (drf != null) drf.delete();
      Scope.exit();
    }
  }
}
