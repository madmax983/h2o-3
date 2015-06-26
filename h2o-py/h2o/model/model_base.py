"""
This module implements the base model class.  All model things inherit from this class.
"""

import h2o
from . import H2OFrame
from . import H2OVec
from . import H2OTwoDimTable
from . import H2OConnection


class ModelBase(object):
  def __init__(self, dest_key, model_json, metrics_class):
    self._key = dest_key

    # setup training metrics
    if "training_metrics" in model_json["output"]:
      tm = model_json["output"]["training_metrics"]
      tm = metrics_class(tm,True,False,model_json["algo"])
      model_json["output"]["training_metrics"] = tm

    # setup validation metrics
    if "validation_metrics" in model_json["output"]:
      vm = model_json["output"]["validation_metrics"]
      if vm is None:
        model_json["output"]["validation_metrics"] = None
      else:
        vm = metrics_class(vm,False,True,model_json["algo"])
        model_json["output"]["validation_metrics"] = vm
    else:
      model_json["output"]["validation_metrics"] = None

    self._model_json = model_json
    self._metrics_class = metrics_class

  def __repr__(self):
    self.show()
    return ""

  def predict(self, test_data):
    """
    Predict on a dataset.

    :param test_data: Data to be predicted on.
    :return: A new H2OFrame filled with predictions.
    """
    if not test_data: raise ValueError("Must specify test data")
    # cbind the test_data vecs together and produce a temp key
    test_data_key = H2OFrame.send_frame(test_data)
    # get the predictions
    # this job call is blocking
    j = H2OConnection.post_json("Predictions/models/" + self._key + "/frames/" + test_data_key)
    # toast the cbound frame
    h2o.removeFrameShallow(test_data_key)
    # retrieve the prediction frame
    prediction_frame_key = j["model_metrics"][0]["predictions"]["frame_id"]["name"]
    # get the actual frame meta dta
    pred_frame_meta = h2o.frame(prediction_frame_key)["frames"][0]
    # toast the prediction frame
    h2o.removeFrameShallow(prediction_frame_key)
    # collect the vec_ids
    vec_ids = pred_frame_meta["vec_ids"]
    # get the number of rows
    rows = pred_frame_meta["rows"]
    # get the column names
    cols = [col["label"] for col in pred_frame_meta["columns"]]
    # create a set of H2OVec objects
    vecs = H2OVec.new_vecs(zip(cols, vec_ids), rows)
    # return a new H2OFrame object
    return H2OFrame(vecs=vecs)

  def deepfeatures(self, test_data, layer):
    """
    Return hidden layer details

    :param test_data: Data to create a feature space on
    :param layer: 0 index hidden layer
    """
    if not test_data: raise ValueError("Must specify test data")
    # create test_data by cbinding vecs
    test_data_key = H2OFrame.send_frame(test_data)
    # get the deepfeatures of the dataset
    j = H2OConnection.post_json("Predictions/models/" + self._key + "/frames/" + test_data_key, deep_features_hidden_layer=layer)
    # retreive the frame data
    deepfeatures_frame_key = j["predictions_frame"]["name"]
    df_frame_meta = h2o.frame(deepfeatures_frame_key)["frames"][0]
    # create vecs by extracting vec_ids, col length, and col names
    vec_ids = df_frame_meta["vec_ids"]
    rows = df_frame_meta["rows"]
    cols = [col["label"] for col in df_frame_meta["columns"]]
    vecs = H2OVec.new_vecs(zip(cols, vec_ids), rows)
    # remove test data from kv
    h2o.removeFrameShallow(test_data_key)
    # finally return frame
    return H2OFrame(vecs=vecs)

  def weights(self, matrix_id=0):
    """
    Return the frame for the respective weight matrix
    :param: matrix_id: an integer, ranging from 0 to number of layers, that specifies the weight matrix to return.
    :return: an H2OFrame which represents the weight matrix identified by matrix_id
    """
    num_weight_matrices = len(self._model_json['output']['weights'])
    if matrix_id not in range(num_weight_matrices):
      raise ValueError("Weight matrix does not exist. Model has {0} weight matrices (0-based indexing), but matrix {1} "
                       "was requested.".format(num_weight_matrices, matrix_id))
    j = h2o.frame(self._model_json['output']['weights'][matrix_id]['URL'].split('/')[3])
    fr = j['frames'][0]
    rows = fr['rows']
    vec_ids = fr['vec_ids']
    cols = fr['columns']
    colnames = [col['label'] for col in cols]
    result = H2OFrame(vecs=H2OVec.new_vecs(zip(colnames, vec_ids), rows))
    return result

  def biases(self, vector_id=0):
    """
    Return the frame for the respective bias vector
    :param: vector_id: an integer, ranging from 0 to number of layers, that specifies the bias vector to return.
    :return: an H2OFrame which represents the bias vector identified by vector_id
    """
    num_bias_vectors = len(self._model_json['output']['biases'])
    if vector_id not in range(num_bias_vectors):
      raise ValueError("Bias vector does not exist. Model has {0} bias vectors (0-based indexing), but vector {1} "
                       "was requested.".format(num_bias_vectors, vector_id))
    j = h2o.frame(self._model_json['output']['biases'][vector_id]['URL'].split('/')[3])
    fr = j['frames'][0]
    rows = fr['rows']
    vec_ids = fr['vec_ids']
    cols = fr['columns']
    colnames = [col['label'] for col in cols]
    result = H2OFrame(vecs=H2OVec.new_vecs(zip(colnames, vec_ids), rows))
    return result

  def model_performance(self, test_data=None, train=False, valid=False):
    """
    Generate model metrics for this model on test_data.

    :param test_data: Data set for which model metrics shall be computed against. Both train and valid arguments are ignored if test_data is not None.
    :param train: Report the training metrics for the model. If the test_data is the training data, the training metrics are returned.
    :param valid: Report the validation metrics for the model. If train and valid are True, then it defaults to True.
    :return: An object of class H2OModelMetrics.
    """
    if test_data is None:
      if not train and not valid:
        train = True  # default to train

      if train:
        return self._model_json["output"]["training_metrics"]

      if valid:
        return self._model_json["output"]["validation_metrics"]

    else:  # cases dealing with test_data not None
      if not isinstance(test_data, H2OFrame):
        raise ValueError("`test_data` must be of type H2OFrame.  Got: " + type(test_data))
      fr_key = H2OFrame.send_frame(test_data)
      res = H2OConnection.post_json("ModelMetrics/models/" + self._key + "/frames/" + fr_key)
      h2o.removeFrameShallow(fr_key)

      # FIXME need to do the client-side filtering...  PUBDEV-874:   https://0xdata.atlassian.net/browse/PUBDEV-874
      raw_metrics = None
      for mm in res["model_metrics"]:
        if mm["frame"]["name"] == fr_key:
          raw_metrics = mm
          break
      return self._metrics_class(raw_metrics,algo=self._model_json["algo"])

  def score_history(self):
    """
    Retrieve Model Score History
    :return: the score history (H2OTwoDimTable)
    """
    model = self._model_json["output"]
    if 'scoring_history' in model.keys() and model["scoring_history"] != None: return model["scoring_history"]
    else: print "No score history for this model"


  def summary(self):
    """
    Print a detailed summary of the model.

    :return:
    """
    model = self._model_json["output"]
    if model["model_summary"]:
      model["model_summary"].show()  # H2OTwoDimTable object


  def show(self):
    """
    Print innards of model, without regards to type

    :return: None
    """
    model = self._model_json["output"]
    print "Model Details"
    print "============="

    print self.__class__.__name__, ": ", self._model_json["algo_full_name"]
    print "Model Key: ", self._key

    self.summary()

    print
    # training metrics
    tm = model["training_metrics"]
    if tm: tm.show()
    vm = model["validation_metrics"]
    if vm: vm.show()

    if "scoring_history" in model.keys() and model["scoring_history"]: model["scoring_history"].show()
    if "variable_importances" in model.keys() and model["variable_importances"]: model["variable_importances"].show()

  def varimp(self, return_list=False):
    """
    Pretty print the variable importances, or return them in a list
    :param return_list: if True, then return the variable importances in an list (ordered from most important to least
    important). Each entry in the list is a 4-tuple of (variable, relative_importance, scaled_importance, percentage).
    :return: None or ordered list
    """
    model = self._model_json["output"]
    if "variable_importances" in model.keys() and model["variable_importances"]:
      if not return_list: return model["variable_importances"].show()
      else: return model["variable_importances"].cell_values
    else:
      print "Warning: This model doesn't have variable importances"

  def residual_deviance(self,train=False,valid=False):
    """
    Retreive the residual deviance if this model has the attribute, or None otherwise.

    :param train: Get the residual deviance for the training set. If both train and valid are False, then train is selected by default.
    :param valid: Get the residual deviance for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the residual deviance, or None if it is not present.
    """
    if not train and not valid:
      train = True
    if train and valid:
      train = True

    if train:
      return self._model_json["output"]["training_metrics"].residual_deviance()
    else:
      return self._model_json["output"]["validation_metrics"].residual_deviance()

  def residual_degrees_of_freedom(self,train=False,valid=False):
    """
    Retreive the residual degress of freedom if this model has the attribute, or None otherwise.

    :param train: Get the residual dof for the training set. If both train and valid are False, then train is selected by default.
    :param valid: Get the residual dof for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the residual dof, or None if it is not present.
    """
    if not train and not valid:
      train = True
    if train and valid:
      train = True

    if train:
      return self._model_json["output"]["training_metrics"].residual_degrees_of_freedom()
    else:
      return self._model_json["output"]["validation_metrics"].residual_degrees_of_freedom()

  def null_deviance(self,train=False,valid=False):
    """
    Retreive the null deviance if this model has the attribute, or None otherwise.

    :param:  train Get the null deviance for the training set. If both train and valid are False, then train is selected by default.
    :param:  valid Get the null deviance for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the null deviance, or None if it is not present.
    """
    if not train and not valid:
      train = True
    if train and valid:
      train = True

    if train:
      return self._model_json["output"]["training_metrics"].null_deviance()
    else:
      return self._model_json["output"]["validation_metrics"].null_deviance()

  def null_degrees_of_freedom(self,train=False,valid=False):
    """
    Retreive the null degress of freedom if this model has the attribute, or None otherwise.

    :param train: Get the null dof for the training set. If both train and valid are False, then train is selected by default.
    :param valid: Get the null dof for the validation set. If both train and valid are True, then train is selected by default.
    :return: Return the null dof, or None if it is not present.
    """
    if not train and not valid:
      train = True
    if train and valid:
      train = True

    if train:
      return self._model_json["output"]["training_metrics"].null_degrees_of_freedom()
    else:
      return self._model_json["output"]["validation_metrics"].null_degrees_of_freedom()

  def pprint_coef(self):
    """
    Pretty print the coefficents table (includes normalized coefficients)
    :return: None
    """
    print self._model_json["output"]["coefficients_table"]  # will return None if no coefs!

  def coef(self):
    """
    :return: Return the coefficients for this model.
    """
    tbl = self._model_json["output"]["coefficients_table"]
    if tbl is None: return None
    tbl = tbl.cell_values
    return {a[0]:a[1] for a in tbl}

  def coef_norm(self):
    """
    :return: Return the normalized coefficients
    """
    tbl = self._model_json["output"]["coefficients_table"]
    if tbl is None: return None
    tbl = tbl.cell_values
    return {a[0]:a[2] for a in tbl}

  def r2(self, train=False, valid=False):
    """
    Return the R^2 for this regression model.

    The R^2 value is defined to be 1 - MSE/var,
    where var is computed as sigma*sigma.

    :param train: If train is True, then return the R^2 value for the training data. If train and valid are both False, then return the training R^2.
    :param valid: If valid is True, then return the R^2 value for the validation data. If train and valid are both True, then return the validation R^2.
    :return: The R^2 for this regression model.
    """
    tm = ModelBase._get_metrics(self, *ModelBase._train_or_valid(train,valid))
    if tm is None: return None
    return tm.r2()

  def mse(self, train=False,valid=False):
    """
    :param train: If train is True, then return the MSE value for the training data. If train and valid are both False, then return the training MSE.
    :param valid: If valid is True, then return the MSE value for the validation data. If train and valid are both True, then return the validation MSE.
    :return: The MSE for this regression model.
    """
    tm = ModelBase._get_metrics(self, *ModelBase._train_or_valid(train,valid))
    if tm is None: return None
    return tm.mse()

  def logloss(self, train=False, valid=False):
    """
    Get the Log Loss.
    If both train and valid are False, return the train.
    If both train and valid are True, return the valid.

    :param train: Return the log loss for training data.
    :param valid: Return the log loss for the validation data.
    :return: Retrieve the log loss coefficient for this set of metrics
    """
    tm = ModelBase._get_metrics(self,*ModelBase._train_or_valid(train, valid))
    if tm is None: return None
    return tm.logloss()

  def auc(self, train=False, valid=False):
    """
    Get the AUC.
    If both train and valid are False, return the train.
    If both train and valid are True, return the valid.

    :param train: Return the AUC for training data.
    :param valid: Return the AUC for the validation data.
    :return: Retrieve the AUC coefficient for this set of metrics
    """
    tm = ModelBase._get_metrics(self,*ModelBase._train_or_valid(train, valid))
    if tm is None: return None
    tm = tm._metric_json
    return tm["AUC"]

  def aic(self, train=False, valid=False):
    """
    Get the AIC.
    If both train and valid are False, return the train.
    If both train and valid are True, return the valid.

    :param train: Return the AIC for training data.
    :param valid: Return the AIC for the validation data.
    :return: Retrieve the AIC for this set of metrics
    """
    tm = ModelBase._get_metrics(self,*ModelBase._train_or_valid(train, valid))
    if tm is None: return None
    tm = tm._metric_json
    return tm["AIC"]

  def giniCoef(self, train=False, valid=False):
    """
    Get the Gini.
    If both train and valid are False, return the train.
    If both train and valid are True, return the valid.

    :param train: Return the Gini for training data.
    :param valid: Return the Gini for the validation data.
    :return: Retrieve the Gini coefficient for this set of metrics
    """
    tm = ModelBase._get_metrics(self, *ModelBase._train_or_valid(train, valid))
    if tm is None: return None
    tm = tm._metric_json
    return tm.giniCoef()

  def download_pojo(self,path=""):
    """
    Download the POJO for this model to the directory specified by path (no trailing slash!).
    If path is "", then dump to screen.
    :param model: Retrieve this model's scoring POJO.
    :param path:  An absolute path to the directory where POJO should be saved.
    :return: None
    """
    h2o.download_pojo(self,path)  # call the "package" function

  @staticmethod
  def _get_metrics(o, train, valid):
    if train:
      return o._model_json["output"]["training_metrics"]
    if valid:
      return o._model_json["output"]["validation_metrics"]
    raise ValueError("`_get_metrics` demands `train` or `valid` to be True.")

  @staticmethod
  def _train_or_valid(train,valid):
    """
    Internal static method.

    :param train: a boolean for train. Ignored, however.
    :param valid: a boolean for valid
    :return: true if train, false if valid. If both are false, return True for train.
    """
    if valid: return [False, True]
    return [True,False]

  # Delete from cluster as model goes out of scope
  def __del__(self):
    h2o.remove(self._key)

  @staticmethod
  def _has(dictionary, key):
    return key in dictionary and dictionary[key] is not None
