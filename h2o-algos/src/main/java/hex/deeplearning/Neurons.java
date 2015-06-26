package hex.deeplearning;

import hex.DataInfo;
import water.*;
import static water.fvec.Vec.makeCon;
import water.util.ArrayUtils;
import water.util.MathUtils;
import water.util.RandomUtils;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * This class implements the concept of a Neuron layer in a Neural Network
 * During training, every MRTask F/J thread is expected to create these neurons for every map call (Cheap to make).
 * These Neurons are NOT sent over the wire.
 * The weights connecting the neurons are in a separate class (DeepLearningModel.DeepLearningModelInfo), and will be shared per node.
 */
public abstract class Neurons {
  protected int units;

  /**
   * Constructor of a Neuron Layer
   * @param units How many neurons are in this layer?
   */
  Neurons(int units) {
    this.units = units;
  }

  /**
   * Print the status of this neuron layer
   * @return populated String
   */
  @Override
  public String toString() {
    String s = this.getClass().getSimpleName();
    s += "\nNumber of Neurons: " + units;
    s += "\nParameters:\n" + params.toString();
    if (_dropout != null) s += "\nDropout:\n" + _dropout.toString();
    return s;
  }

  /**
   * Parameters (deep-cloned() from the user input, can be modified here, e.g. learning rate decay)
   */
  protected transient DeepLearningParameters params;
  protected transient int _index; //which hidden layer it is

  /**
   * Layer state (one per neuron): activity, error
   */
  public transient Storage.Vector _a; //can be sparse for input layer
  public transient Storage.DenseVector _e;

  /**
   * References for feed-forward connectivity
   */
  public Neurons _previous;
  public Neurons _input;
  DeepLearningModelInfo _minfo; //reference to shared model info
  public Storage.Matrix _w;
  public Storage.Matrix _wEA; //weights for elastic averaging
  public Storage.DenseVector _b;
  public Storage.DenseVector _bEA; //bias for elastic averaging

  /**
   * References for momentum training
   */
  Storage.Matrix _wm;
  Storage.DenseVector _bm;

  /**
   * References for ADADELTA
   */
  Storage.Matrix _ada_dx_g;
  Storage.DenseVector _bias_ada_dx_g;

  /**
   * For Dropout training
   */
  protected Dropout _dropout;

  /**
   * Helper to shortcut bprop
   */
  private boolean _shortcut = false;

  public Storage.DenseVector _avg_a;

  public static final int missing_int_value = Integer.MAX_VALUE; //encode missing label
  public static final Float missing_real_value = Float.NaN; //encode missing regression target

  /**
   * Helper to check sanity of Neuron layers
   * @param training whether training or testing is done
   */
  void sanityCheck(boolean training) {
    if (this instanceof Input) {
      assert(_previous == null);
      assert (!training || _dropout != null);
    } else {
      assert(_previous != null);
      if (_minfo.has_momenta()) {
        assert(_wm != null);
        assert(_bm != null);
        assert(_ada_dx_g == null);
      }
      if (_minfo.adaDelta()) {
        if (params._rho == 0) throw new IllegalArgumentException("rho must be > 0 if epsilon is >0.");
        if (params._epsilon == 0) throw new IllegalArgumentException("epsilon must be > 0 if rho is >0.");
        assert(_minfo.adaDelta());
        assert(_bias_ada_dx_g != null);
        assert(_wm == null);
        assert(_bm == null);
      }
      if (this instanceof MaxoutDropout || this instanceof TanhDropout || this instanceof RectifierDropout) {
        assert (!training || _dropout != null);
      }
    }
  }

  /**
   * Initialization of the parameters and connectivity of a Neuron layer
   * @param neurons Array of all neuron layers, to establish feed-forward connectivity
   * @param index Which layer am I?
   * @param p User-given parameters (Job parental object hierarchy is not used)
   * @param minfo Model information (weights/biases and their momenta)
   * @param training Whether training is done or just testing (no need for dropout)
   */
  public final void init(Neurons[] neurons, int index, DeepLearningParameters p, final DeepLearningModelInfo minfo, boolean training) {
    _index = index-1;
    params = (DeepLearningParameters)p.clone();
    params._hidden_dropout_ratios = minfo.get_params()._hidden_dropout_ratios;
    params._rate *= Math.pow(params._rate_decay, index-1);
    _a = new Storage.DenseVector(units);
    if (!(this instanceof Output) && !(this instanceof Input)) {
      _e = new Storage.DenseVector(units);
    }
    if (training && (this instanceof MaxoutDropout || this instanceof TanhDropout
            || this instanceof RectifierDropout || this instanceof Input) ) {
      _dropout = this instanceof Input ? new Dropout(units, params._input_dropout_ratio) : new Dropout(units, params._hidden_dropout_ratios[_index]);
    }
    if (!(this instanceof Input)) {
      _previous = neurons[_index]; //incoming neurons
      _minfo = minfo;
      _w = minfo.get_weights(_index); //incoming weights
      _b = minfo.get_biases(_index); //bias for this layer (starting at hidden layer)
      if(params._autoencoder && params._sparsity_beta > 0 && _index < params._hidden.length) {
        _avg_a = minfo.get_avg_activations(_index);
      }
      if (minfo.has_momenta()) {
        _wm = minfo.get_weights_momenta(_index); //incoming weights
        _bm = minfo.get_biases_momenta(_index); //bias for this layer (starting at hidden layer)
      }
      if (minfo.adaDelta()) {
        _ada_dx_g = minfo.get_ada_dx_g(_index);
        _bias_ada_dx_g = minfo.get_biases_ada_dx_g(_index);
      }
      _shortcut = (params._fast_mode || (
              // not doing fast mode, but also don't have anything else to update (neither momentum nor ADADELTA history), and no L1/L2
              !params._adaptive_rate && !_minfo.has_momenta() && params._l1 == 0.0 && params._l2 == 0.0));
    }
    sanityCheck(training);
  }

  /**
   * Forward propagation
   * @param seed For seeding the RNG inside (for dropout)
   * @param training Whether training is done or just testing (no need for dropout)
   */
  protected abstract void fprop(long seed, boolean training);

  /**
   *  Back propagation
   */
  protected abstract void bprop();

  void bprop_sparse(float r, float m) {
    Storage.SparseVector prev_a = (Storage.SparseVector) _previous._a;
    int start = prev_a.begin()._idx;
    int end = prev_a.end()._idx;
    for (int it = start; it < end; ++it) {
      final int col = prev_a._indices[it];
      final float previous_a = prev_a._values[it];
      bprop_col(col, previous_a, r, m);
    }
    final int rows = _a.size();
    final float max_w2 = params._max_w2;
    for (int row = 0; row < rows; row++) {
      if (max_w2 != Float.POSITIVE_INFINITY)
        rescale_weights(_w, row, max_w2);
    }
  }

  /**
   * Backpropagation: w -= rate * dE/dw, where dE/dw = dE/dy * dy/dnet * dnet/dw
   * This method adds the dnet/dw = activation term per unit
   * @param row row index (update weights feeding to this neuron)
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  final void bprop(final int row, final float partial_grad, final float rate, final float momentum) {
    // only correct weights if the gradient is large enough
    if (_shortcut && partial_grad == 0f) return;

    if (_w instanceof Storage.DenseRowMatrix && _previous._a instanceof Storage.DenseVector)
      bprop_dense_row_dense(
              (Storage.DenseRowMatrix) _w, (Storage.DenseRowMatrix) _wEA, (Storage.DenseRowMatrix) _wm, (Storage.DenseRowMatrix) _ada_dx_g,
              (Storage.DenseVector) _previous._a, _previous._e, _b, _bEA, _bm, row, partial_grad, rate, momentum);
    else if (_w instanceof Storage.DenseRowMatrix && _previous._a instanceof Storage.SparseVector)
      bprop_dense_row_sparse(
              (Storage.DenseRowMatrix)_w, (Storage.DenseRowMatrix)_wm, (Storage.DenseRowMatrix)_ada_dx_g,
              (Storage.SparseVector)_previous._a, _previous._e, _b, _bm, row, partial_grad, rate, momentum);
    else
      throw new UnsupportedOperationException("bprop for types not yet implemented.");
  }

  final void bprop_col(final int col, final float previous_a, final float rate, final float momentum) {
    if (_w instanceof Storage.DenseColMatrix && _previous._a instanceof Storage.SparseVector)
      bprop_dense_col_sparse(
              (Storage.DenseColMatrix)_w, (Storage.DenseColMatrix)_wm, (Storage.DenseColMatrix)_ada_dx_g,
              (Storage.SparseVector)_previous._a, _previous._e, _b, _bm, col, previous_a, rate, momentum);
    else
      throw new UnsupportedOperationException("bprop_col for types not yet implemented.");
  }

  /**
   * Specialization of backpropagation for DenseRowMatrices and DenseVectors
   * @param _w weight matrix
   * @param _w elastic average weight matrix
   * @param _wm weight momentum matrix
   * @param adaxg ADADELTA matrix (2 floats per weight)
   * @param prev_a activation of previous layer
   * @param prev_e error of previous layer
   * @param _b bias vector
   * @param _bm bias momentum vector
   * @param row index of the neuron for which we back-propagate
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  private void bprop_dense_row_dense(
          final Storage.DenseRowMatrix _w, final Storage.DenseRowMatrix _wEA, final Storage.DenseRowMatrix _wm, final Storage.DenseRowMatrix adaxg,
          final Storage.DenseVector prev_a, final Storage.DenseVector prev_e, final Storage.DenseVector _b, final Storage.DenseVector _bEA, final Storage.DenseVector _bm,
          final int row, final float partial_grad, float rate, final float momentum)
  {
    final float rho = (float)params._rho;
    final float eps = (float)params._epsilon;
    final float l1 = (float)params._l1;
    final float l2 = (float)params._l2;
    final float max_w2 = params._max_w2;
    final boolean have_momenta = _minfo.has_momenta();
    final boolean have_ada = _minfo.adaDelta();
    final boolean nesterov = params._nesterov_accelerated_gradient;
    final boolean update_prev = prev_e != null;
    final boolean fast_mode = params._fast_mode;
    final int cols = prev_a.size();
    final int idx = row * cols;

    float avg_grad2 = 0;
    for( int col = 0; col < cols; col++ ) {
      final float weight = _w.get(row,col);
      if( update_prev ) prev_e.add(col, partial_grad * weight); // propagate the error dE/dnet to the previous layer, via connecting weights
      final float previous_a = prev_a.get(col);
      if (fast_mode && previous_a == 0) continue;

      //this is the actual gradient dE/dw
      final int w = idx + col;
      float grad = partial_grad * previous_a - Math.signum(weight) * l1 - weight * l2;
      if (_wEA !=null) grad -= params._elastic_averaging_regularization * (_w.raw()[w] -_wEA.raw()[w]);

      if (have_ada) {
        final float grad2 = grad*grad;
        avg_grad2 += grad2;
        float brate = computeAdaDeltaRateForWeight(grad, w, adaxg, rho, eps);
        _w.raw()[w] += brate * grad;
      } else {
        if (!nesterov) {
          final float delta = rate * grad;
          _w.raw()[w] += delta;
          if( have_momenta ) {
            _w.raw()[w] += momentum * _wm.raw()[w];
            _wm.raw()[w] = delta;
          }
        } else {
          float tmp = grad;
          if( have_momenta ) {
            _wm.raw()[w] *= momentum;
            _wm.raw()[w] += tmp;
            tmp = _wm.raw()[w];
          }
          _w.raw()[w] += rate * tmp;
        }
      }
    }
    if (max_w2 != Float.POSITIVE_INFINITY)
      rescale_weights(_w, row, max_w2);
    if (have_ada) avg_grad2 /= cols;
    update_bias(_b, _bEA, _bm, row, partial_grad, avg_grad2, rate, momentum);
  }

  /**
   * Specialization of backpropagation for DenseColMatrices and SparseVector for previous layer's activation and DenseVector for everything else
   * @param w Weight matrix
   * @param wm Momentum matrix
   * @param adaxg ADADELTA matrix (2 floats per weight)
   * @param prev_a sparse activation of previous layer
   * @param prev_e error of previous layer
   * @param b bias
   * @param bm bias momentum
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  private void bprop_dense_col_sparse(
          final Storage.DenseColMatrix w, final Storage.DenseColMatrix wm, final Storage.DenseColMatrix adaxg,
          final Storage.SparseVector prev_a, final Storage.DenseVector prev_e, final Storage.DenseVector b, final Storage.DenseVector bm,
          final int col, final float previous_a, float rate, final float momentum)
  {
    final float rho = (float)params._rho;
    final float eps = (float)params._epsilon;
    final float l1 = (float)params._l1;
    final float l2 = (float)params._l2;
    final boolean have_momenta = _minfo.has_momenta();
    final boolean have_ada = _minfo.adaDelta();
    final boolean nesterov = params._nesterov_accelerated_gradient;
    final boolean update_prev = prev_e != null;
    final int cols = prev_a.size();

    final int rows = _a.size();
    for (int row = 0; row < rows; row++) {
      final float partial_grad = _e.get(row) * (1f - _a.get(row) * _a.get(row));
      final float weight = w.get(row,col);
      if( update_prev ) prev_e.add(col, partial_grad * weight); // propagate the error dE/dnet to the previous layer, via connecting weights
      assert (previous_a != 0); //only iterate over non-zeros!

      if (_shortcut && partial_grad == 0f) continue;

      //this is the actual gradient dE/dw
      float grad = partial_grad * previous_a - Math.signum(weight) * l1 - weight * l2;
      if (_wEA !=null) throw H2O.unimpl("elastic averaging is not implemented for sparse input handling with column-major matrix format.");
      if (have_ada) {
        assert(!have_momenta);
        float brate = computeAdaDeltaRateForWeight(grad, row, col, adaxg, rho, eps);
        w.add(row,col, brate * grad);
      } else {
        if (!nesterov) {
          final float delta = rate * grad;
          w.add(row, col, delta);
//          Log.info("for row = " + row + ", col = " + col + ", partial_grad = " + partial_grad + ", grad = " + grad);
          if( have_momenta ) {
            w.add(row, col, momentum * wm.get(row, col));
            wm.set(row, col, delta);
          }
        } else {
          float tmp = grad;
          if( have_momenta ) {
            float val = wm.get(row, col);
            val *= momentum;
            val += tmp;
            tmp = val;
            wm.set(row, col, val);
          }
          w.add(row, col, rate * tmp);
        }
      }
      //this is called cols times, so we divide the (repeated) contribution by 1/cols
      assert(_bEA == null); //not yet implemented
      update_bias(b, _bEA, bm, row, partial_grad/cols, grad*grad/cols, rate, momentum);
    }
  }

 /**
   * Specialization of backpropagation for DenseRowMatrices and SparseVector for previous layer's activation and DenseVector for everything else
   * @param _w weight matrix
   * @param _wm weight momentum matrix
   * @param adaxg ADADELTA matrix (2 floats per weight)
   * @param prev_a sparse activation of previous layer
   * @param prev_e error of previous layer
   * @param _b bias vector
   * @param _bm bias momentum vector
   * @param row index of the neuron for which we back-propagate
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  private void bprop_dense_row_sparse(
          final Storage.DenseRowMatrix _w, final Storage.DenseRowMatrix _wm, final Storage.DenseRowMatrix adaxg,
          final Storage.SparseVector prev_a, final Storage.DenseVector prev_e, final Storage.DenseVector _b, final Storage.DenseVector _bm,
          final int row, final float partial_grad, float rate, final float momentum)
  {
    final float rho = (float)params._rho;
    final float eps = (float)params._epsilon;
    final float l1 = (float)params._l1;
    final float l2 = (float)params._l2;
    final float max_w2 = params._max_w2;
    final boolean have_momenta = _minfo.has_momenta();
    final boolean have_ada = _minfo.adaDelta();
    final boolean nesterov = params._nesterov_accelerated_gradient;
    final boolean update_prev = prev_e != null;
    final int cols = prev_a.size();
    final int idx = row * cols;

    float avg_grad2 = 0;
    int start = prev_a.begin()._idx;
    int end = prev_a.end()._idx;
    for (int it = start; it < end; ++it) {
      final int col = prev_a._indices[it];
      final float weight = _w.get(row,col);
      if( update_prev ) prev_e.add(col, partial_grad * weight); // propagate the error dE/dnet to the previous layer, via connecting weights
      final float previous_a = prev_a._values[it];
      assert (previous_a != 0); //only iterate over non-zeros!

      //this is the actual gradient dE/dw
      float grad = partial_grad * previous_a - Math.signum(weight) * l1 - weight * l2;
      if (_wEA !=null) throw H2O.unimpl("elastic averaging is not implemented for sparse input handling.");
      final int w = idx + col;

      if (have_ada) {
        assert(!have_momenta);
        final float grad2 = grad*grad;
        avg_grad2 += grad2;
        float brate = computeAdaDeltaRateForWeight(grad, w, adaxg, rho, eps);
        _w.raw()[w] += brate * grad;
      } else {
        if (!nesterov) {
          final float delta = rate * grad;
          _w.raw()[w] += delta;
          if( have_momenta ) {
            _w.raw()[w] += momentum * _wm.raw()[w];
            _wm.raw()[w] = delta;
          }
        } else {
          float tmp = grad;
          if( have_momenta ) {
            _wm.raw()[w] *= momentum;
            _wm.raw()[w] += tmp;
            tmp = _wm.raw()[w];
          }
          _w.raw()[w] += rate * tmp;
        }
      }
    }
    if (max_w2 != Float.POSITIVE_INFINITY)
      rescale_weights(_w, row, max_w2);
    if (have_ada) avg_grad2 /= prev_a.nnz();
    assert(_bEA == null); //not yet implemented
    update_bias(_b, _bEA, _bm, row, partial_grad, avg_grad2, rate, momentum);
  }

  /**
   * Helper to scale down incoming weights if their squared sum exceeds a given value (by a factor of 10 -> to avoid doing costly rescaling too often)
   * C.f. Improving neural networks by preventing co-adaptation of feature detectors
   * @param row index of the neuron for which to scale the weights
   */
  private static void rescale_weights(final Storage.Matrix w, final int row, final float max_w2) {
    final int cols = w.cols();
    if (w instanceof Storage.DenseRowMatrix) {
      rescale_weights((Storage.DenseRowMatrix)w, row, max_w2);
    } else if (w instanceof Storage.DenseColMatrix) {
      float r2 = 0;
      for (int col=0; col<cols;++col)
        r2 += w.get(row,col)*w.get(row,col);
      if( r2 > max_w2) {
        final float scale = MathUtils.approxSqrt(max_w2 / r2);
        for( int col=0; col < cols; col++ ) w.set(row, col, w.get(row,col) * scale);
      }
    }
    else throw new UnsupportedOperationException("rescale weights for " + w.getClass().getSimpleName() + " not yet implemented.");
  }

  // Specialization for DenseRowMatrix
  private static void rescale_weights(final Storage.DenseRowMatrix w, final int row, final float max_w2) {
    final int cols = w.cols();
    final int idx = row * cols;
    float r2 = MathUtils.sumSquares(w.raw(), idx, idx + cols);
//    float r2 = MathUtils.approxSumSquares(w.raw(), idx, idx + cols);
    if( r2 > max_w2) {
      final float scale = MathUtils.approxSqrt(max_w2 / r2);
      for( int c = 0; c < cols; c++ ) w.raw()[idx + c] *= scale;
    }
  }

  /**
   * Helper to compute the reconstruction error for auto-encoders (part of the gradient computation)
   * @param row neuron index
   * @return difference between the output (auto-encoder output layer activation) and the target (input layer activation)
   */
  protected float autoEncoderError(int row) {
    assert (_minfo.get_params()._autoencoder && _index == _minfo.get_params()._hidden.length);
    final float t = _input._a.get(row);
    final float y = _a.get(row);
    float g;
    if (params._loss == DeepLearningParameters.Loss.MeanSquare) {
      g = t - y;
    } else if (params._loss == DeepLearningParameters.Loss.Absolute) {
      g = y > t ? -1f : 1f;
    }
    // Huber:
    // L = (y-t)^2    for |t-y| < 1,  -dL/dy = t - y
    // L = 2*|t-y|-1  for |t-y| >= 1, -dL/dy = +/- 2
    else if (params._loss == DeepLearningParameters.Loss.Huber) {
      if (Math.abs(y-t) < 1) {
        g = t - y;
      } else {
        g = y >= t + 1f ? -2f : 2f;
      }
    } else throw H2O.unimpl("Loss " + params._loss + " not implemented for Auto-Encoder.");
    return g;
  }

  /**
   * Compute learning rate with AdaDelta
   * http://www.matthewzeiler.com/pubs/googleTR2012/googleTR2012.pdf
   * @param grad gradient
   * @param row which neuron is to be updated
   * @param col weight from which incoming neuron
   * @param ada_dx_g Matrix holding helper values (2 floats per weight)
   * @param rho hyper-parameter #1
   * @param eps hyper-parameter #2
   * @return learning rate
   */
  private static float computeAdaDeltaRateForWeight(final float grad, final int row, final int col,
                                                  final Storage.DenseColMatrix ada_dx_g,
                                                  final float rho, final float eps) {
    ada_dx_g.set(2*row+1, col, rho * ada_dx_g.get(2*row+1, col) + (1f - rho) * grad * grad);
    final float rate = MathUtils.approxSqrt((ada_dx_g.get(2*row, col) + eps)/(ada_dx_g.get(2*row+1, col) + eps));
    ada_dx_g.set(2*row,   col, rho * ada_dx_g.get(2*row, col)   + (1f - rho) * rate * rate * grad * grad);
    return rate;
  }

  /**
   * Compute learning rate with AdaDelta, specialized for DenseRowMatrix
   * @param grad gradient
   * @param w neuron index
   * @param ada_dx_g Matrix holding helper values (2 floats per weight)
   * @param rho hyper-parameter #1
   * @param eps hyper-parameter #2
   * @return learning rate
   */
  private static float computeAdaDeltaRateForWeight(final float grad, final int w,
                                                  final Storage.DenseRowMatrix ada_dx_g,
                                                  final float rho, final float eps) {
    ada_dx_g.raw()[2*w+1] = rho * ada_dx_g.raw()[2*w+1] + (1f - rho) * grad * grad;
    final float rate = MathUtils.approxSqrt((ada_dx_g.raw()[2*w] + eps)/(ada_dx_g.raw()[2*w+1] + eps));
    ada_dx_g.raw()[2*w]   = rho * ada_dx_g.raw()[2*w]   + (1f - rho) * rate * rate * grad * grad;
    return rate;
  }

  /**
   * Compute learning rate with AdaDelta, specialized for DenseVector (Bias)
   * @param grad2 squared gradient
   * @param row neuron index
   * @param bias_ada_dx_g Matrix holding helper values (2 floats per weight)
   * @param rho hyper-parameter #1
   * @param eps hyper-parameter #2
   * @return learning rate
   */
  private static float computeAdaDeltaRateForBias(final float grad2, final int row,
                                                  final Storage.DenseVector bias_ada_dx_g,
                                                  final float rho, final float eps) {
    bias_ada_dx_g.raw()[2*row+1] = rho * bias_ada_dx_g.raw()[2*row+1] + (1f - rho) * grad2;
    final float rate = MathUtils.approxSqrt((bias_ada_dx_g.raw()[2*row  ] + eps)/(bias_ada_dx_g.raw()[2*row+1] + eps));
    bias_ada_dx_g.raw()[2*row]   = rho * bias_ada_dx_g.raw()[2*row  ] + (1f - rho) * rate * rate * grad2;
    return rate;
  }

  /**
   * Helper to enforce learning rule to satisfy sparsity constraint:
   * Computes the (rolling) average activation for each (hidden) neuron.
   */
  void compute_sparsity() {
    if (_avg_a != null) {
      for (int row = 0; row < _avg_a.size(); row++) {
        _avg_a.set(row, (float) 0.999 * (_avg_a.get(row)) + (float) 0.001 * (_a.get(row)));
      }
    }
  }

  /**
   * Helper to update the bias values
   * @param _b bias vector
   * @param _bEA elastic average bias vector
   * @param _bm bias momentum vector
   * @param row index of the neuron for which we back-propagate
   * @param partial_grad partial derivative dE/dnet = dE/dy * dy/net
   * @param avg_grad2 average squared gradient for this neuron's incoming weights (only for ADADELTA)
   * @param rate learning rate
   * @param momentum momentum factor (needed only if ADADELTA isn't used)
   */
  void update_bias(final Storage.DenseVector _b, final Storage.DenseVector _bEA, final Storage.DenseVector _bm, final int row,
                   float partial_grad, final float avg_grad2, float rate, final float momentum) {
    final boolean have_momenta = _minfo.has_momenta();
    final boolean have_ada = _minfo.adaDelta();
    final float l1 = (float)params._l1;
    final float l2 = (float)params._l2;
    final float bias = _b.get(row);

    partial_grad -= Math.signum(bias) * l1 + bias * l2;
    if (_bEA != null) partial_grad -= (bias - _bEA.get(row)) * params._elastic_averaging_regularization;

    if (have_ada) {
      final float rho = (float)params._rho;
      final float eps = (float)params._epsilon;
      rate = computeAdaDeltaRateForBias(avg_grad2, row, _bias_ada_dx_g, rho, eps);
    }
    if (!params._nesterov_accelerated_gradient) {
      final float delta = rate * partial_grad;
      _b.add(row, delta);
      if (have_momenta) {
        _b.add(row, momentum * _bm.get(row));
        _bm.set(row, delta);
      }
    } else {
      float d = partial_grad;
      if (have_momenta) {
        _bm.set(row, _bm.get(row) * momentum);
        _bm.add(row, d);
        d = _bm.get(row);
      }
      _b.add(row, rate * d);
    }
    //update for sparsity constraint
    if (params._autoencoder && params._sparsity_beta > 0 && !(this instanceof Output) && !(this instanceof Input) && (_index != params._hidden.length)) {
      _b.add(row, -(float) (rate * params._sparsity_beta * (_avg_a.raw()[row] - params._average_activation)));
    }
    if (Float.isInfinite(_b.get(row))) _minfo.set_unstable();
  }


  /**
   * The learning rate
   * @param n The number of training samples seen so far (for rate_annealing greater than 0)
   * @return Learning rate
   */
  public float rate(double n) {
    return (float)(params._rate / (1 + params._rate_annealing * n));
  }

  protected float momentum() {
    return momentum(-1);
  }
  /**
   * The momentum - real number in [0, 1)
   * Can be a linear ramp from momentum_start to momentum_stable, over momentum_ramp training samples
   * @param n The number of training samples seen so far
   * @return momentum
   */
  public float momentum(double n) {
    double m = params._momentum_start;
    if( params._momentum_ramp > 0 ) {
      final double num = n != -1 ? _minfo.get_processed_total() : n;
      if( num >= params._momentum_ramp)
        m = params._momentum_stable;
      else
        m += (params._momentum_stable - params._momentum_start) * num / params._momentum_ramp;
    }
    return (float)m;
  }

  /**
   * Input layer of the Neural Network
   * This layer is different from other layers as it has no incoming weights,
   * but instead gets its activation values from the training points.
   */
  public static class Input extends Neurons {

    private DataInfo _dinfo; //training data
    Storage.SparseVector _svec;
    Storage.DenseVector _dvec;

    Input(int units, final DataInfo d) {
      super(units);
      _dinfo = d;
      _a = new Storage.DenseVector(units);
      _dvec = (Storage.DenseVector)_a;
    }

    @Override protected void bprop() { throw new UnsupportedOperationException(); }
    @Override protected void fprop(long seed, boolean training) { throw new UnsupportedOperationException(); }

    /**
     * One of two methods to set layer input values. This one is for raw double data, e.g. for scoring
     * @param seed For seeding the RNG inside (for input dropout)
     * @param data Data (training columns and responses) to extract the training columns
     *             from to be mapped into the input neuron layer
     */
    public void setInput(long seed, final double[] data) {
//      Log.info("Data: " + ArrayUtils.toString(data));
      assert(_dinfo != null);
      double [] nums = MemoryManager.malloc8d(_dinfo._nums); // a bit wasteful - reallocated each time
      int    [] cats = MemoryManager.malloc4(_dinfo._cats); // a bit wasteful - reallocated each time
      int i = 0, ncats = 0;
      for(; i < _dinfo._cats; ++i){
        assert(_dinfo._catMissing[i] != 0); //we now *always* have a categorical level for NAs, just in case.
        if (Double.isNaN(data[i])) {
          cats[ncats] = (_dinfo._catOffsets[i+1]-1); //use the extra level for NAs made during training
        } else {
          int c = (int)data[i];

          if (_dinfo._useAllFactorLevels)
            cats[ncats] = c + _dinfo._catOffsets[i];
          else if (c!=0)
            cats[ncats] = c + _dinfo._catOffsets[i] - 1;

          // If factor level in test set was not seen by training, then turn it into an NA
          if (cats[ncats] >= _dinfo._catOffsets[i+1]) {
            cats[ncats] = (_dinfo._catOffsets[i+1]-1);
          }
        }
        ncats++;
      }
      final int n = data.length - (_dinfo._weights ? 1 : 0) - (_dinfo._offset ? 1 : 0);
      for(;i < n;++i){
        double d = data[i];
        if(_dinfo._normMul != null) d = (d - _dinfo._normSub[i-_dinfo._cats])*_dinfo._normMul[i-_dinfo._cats];
        nums[i-_dinfo._cats] = d; //can be NaN for missing numerical data
      }
      setInput(seed, nums, ncats, cats);
    }

    /**
     * The second method used to set input layer values. This one is used directly by FrameTask.processRow() and by the method above.
     * @param seed For seeding the RNG inside (for input dropout)
     * @param nums Array containing numerical values, can be NaN
     * @param numcat Number of horizontalized categorical non-zero values (i.e., those not being the first factor of a class)
     * @param cats Array of indices, the first numcat values are the input layer unit (==column) indices for the non-zero categorical values
     *             (This allows this array to be re-usable by the caller, without re-allocating each time)
     */
    public void setInput(long seed, final double[] nums, final int numcat, final int[] cats) {
      _a = _dvec;
      Arrays.fill(_a.raw(), 0f);

      // random projection from fullN down to max_categorical_features
      if (params._max_categorical_features < _dinfo.fullN() - _dinfo._nums) {
        assert(nums.length == _dinfo._nums);
        final int M = nums.length + params._max_categorical_features;
        final boolean random_projection = false;
        final boolean hash_trick = true;
        if (random_projection) {
          final int N = _dinfo.fullN();
          assert (_a.size() == M);

          // sparse random projection
          for (int i = 0; i < M; ++i) {
            for (int c = 0; c < numcat; ++c) {
              int j = cats[c];
              Random rng = RandomUtils.getRNG(params._seed + i * N + j); //TODO: re-use same pool of random numbers with some hashing
              float val = 0;
              final float rnd = rng.nextFloat();
              if (rnd < 1. / 6.) val = (float) Math.sqrt(3);
              if (rnd > 5. / 6.) val = -(float) Math.sqrt(3);
              _a.add(i, 1f * val);
            }
            Random rng = RandomUtils.getRNG(params._seed + i*N + _dinfo.numStart());
            for (int n = 0; n < nums.length; ++n) {
              float val = 0;
              final float rnd = rng.nextFloat();
              if (rnd < 1. / 6.) val = (float) Math.sqrt(3);
              if (rnd > 5. / 6.) val = -(float) Math.sqrt(3);
              _a.set(i, (Double.isNaN(nums[n]) ? 0f /*Always do MeanImputation during scoring*/ : (float) nums[n]) * val);
            }
          }
        } else if (hash_trick) {
          // Use hash trick for categorical features
          assert (_a.size() == M);
          // hash N-nums.length down to M-nums.length = cM (#categorical slots - always use all numerical features)
          final int cM = params._max_categorical_features;

          assert (_a.size() == M);
          MurmurHash murmur = MurmurHash.getInstance();
          for (int i = 0; i < numcat; ++i) {
            ByteBuffer buf = ByteBuffer.allocate(4);
            int hashval = murmur.hash(buf.putInt(cats[i]).array(), 4, (int)params._seed); // turn horizontalized categorical integer into another integer, based on seed
            _a.add(Math.abs(hashval % cM), 1f); // restrict to limited range
          }
          for (int i = 0; i < nums.length; ++i)
            _a.set(cM + i, Double.isNaN(nums[i]) ? 0f /*Always do MeanImputation during scoring*/ : (float) nums[i]);
        }
      } else {
        assert(_a.size() == _dinfo.fullN());
        for (int i = 0; i < numcat; ++i) _a.set(cats[i], 1f); // one-hot encode categoricals
        for (int i = 0; i < nums.length; ++i)
          _a.set(_dinfo.numStart() + i, Double.isNaN(nums[i]) ? 0f /*Always do MeanImputation during scoring*/ : (float) nums[i]);
      }

      // Input Dropout
      if (_dropout == null) return;
      seed += params._seed + 0x1337B4BE;
      _dropout.randomlySparsifyActivation(_a, seed);

      if (params._sparse) {
        _svec = new Storage.SparseVector(_dvec);
        _a = _svec;
      }
    }

  }

  /**
   * Tanh neurons - most common, most stable
   */
  public static class Tanh extends Neurons {
    public Tanh(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      gemv((Storage.DenseVector)_a, _w, _previous._a, _b, _dropout != null ? _dropout.bits() : null);
      final int rows = _a.size();
      for( int row = 0; row < rows; row++ )
        _a.set(row, 1f - 2f / (1f + (float)Math.exp(2*_a.get(row)))); //evals faster than tanh(x), but is slightly less numerically stable - OK
      compute_sparsity();
    }
    // Computing partial derivative g = dE/dnet = dE/dy * dy/dnet, where dE/dy is the backpropagated error
    // dy/dnet = (1 - a^2) for y(net) = tanh(net)
    @Override protected void bprop() {
      float m = momentum();
      float r = _minfo.adaDelta() ? 0 : rate(_minfo.get_processed_total()) * (1f - m);
      if (_w instanceof Storage.DenseRowMatrix) {
        final int rows = _a.size();
        for (int row = 0; row < rows; row++) {
          if (_minfo.get_params()._autoencoder && _index == _minfo.get_params()._hidden.length)
            _e.set(row, autoEncoderError(row));
          float g = _e.get(row) * (1f - _a.get(row) * _a.get(row));
          bprop(row, g, r, m);
        }
      }
      else {
        bprop_sparse(r, m);
      }
    }
  }

  /**
   * Tanh neurons with dropout
   */
  public static class TanhDropout extends Tanh {
    public TanhDropout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (training) {
        seed += params._seed + 0xDA7A6000;
        _dropout.fillBytes(seed);
        super.fprop(seed, true);
      }
      else {
        super.fprop(seed, false);
        ArrayUtils.mult(_a.raw(), (float)(1-params._hidden_dropout_ratios[_index]));
      }
    }
  }

  /**
   * Maxout neurons
   */
  public static class Maxout extends Neurons {
    public Maxout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      float max = 0;
      final int rows = _a.size();
      if (_previous._a instanceof Storage.DenseVector) {
        for( int row = 0; row < rows; row++ ) {
          _a.set(row, 0);
          if( !training || _dropout == null || _dropout.unit_active(row) ) {
            _a.set(row, Float.NEGATIVE_INFINITY);
            for( int i = 0; i < _previous._a.size(); i++ )
              _a.set(row, Math.max(_a.get(row), _w.get(row, i) * _previous._a.get(i)));
            if (Float.isInfinite(-_a.get(row))) _a.set(row, 0); //catch the case where there is dropout (and/or input sparsity) -> no max found!
            _a.add(row, _b.get(row));
            max = Math.max(_a.get(row), max);
          }
        }
        if( max > 1 ) ArrayUtils.div(_a.raw(), max);
      }
      else {
        Storage.SparseVector x = (Storage.SparseVector)_previous._a;
        for( int row = 0; row < _a.size(); row++ ) {
          _a.set(row, 0);
          if( !training || _dropout == null || _dropout.unit_active(row) ) {
//            _a.set(row, Float.NEGATIVE_INFINITY);
//            for( int i = 0; i < _previous._a.size(); i++ )
//              _a.set(row, Math.max(_a.get(row), _w.get(row, i) * _previous._a.get(i)));
            float mymax = Float.NEGATIVE_INFINITY;
            int start = x.begin()._idx;
            int end = x.end()._idx;
            for (int it = start; it < end; ++it) {
              mymax = Math.max(mymax, _w.get(row, x._indices[it]) * x._values[it]);
            }
            _a.set(row, mymax);
            if (Float.isInfinite(-_a.get(row))) _a.set(row, 0); //catch the case where there is dropout (and/or input sparsity) -> no max found!
            _a.add(row, _b.get(row));
            max = Math.max(_a.get(row), max);
          }
        }
        if( max > 1f ) ArrayUtils.div(_a.raw(), max);
      }
      compute_sparsity();
    }
    @Override protected void bprop() {
      float m = momentum();
      float r = _minfo.adaDelta() ? 0 : rate(_minfo.get_processed_total()) * (1f - m);
      if (_w instanceof Storage.DenseRowMatrix) {
        final int rows = _a.size();
        for( int row = 0; row < rows; row++ ) {
          assert (!_minfo.get_params()._autoencoder);
//          if (_minfo.get_params().autoencoder && _index == _minfo.get_params().hidden.length)
//            _e.set(row, autoEncoderError(row));
          float g = _e.get(row);
//                if( _a[o] < 0 )   Not sure if we should be using maxout with a hard zero bottom
//                    g = 0;
          bprop(row, g, r, m);
        }
      }
      else {
        bprop_sparse(r, m);
      }
    }
  }

  /**
   * Maxout neurons with dropout
   */
  public static class MaxoutDropout extends Maxout {
    public MaxoutDropout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (training) {
        seed += params._seed + 0x51C8D00D;
        _dropout.fillBytes(seed);
        super.fprop(seed, true);
      }
      else {
        super.fprop(seed, false);
        ArrayUtils.mult(_a.raw(), (float)(1-params._hidden_dropout_ratios[_index]));
      }
    }
  }

  /**
   * Rectifier linear unit (ReLU) neurons
   */
  public static class Rectifier extends Neurons {
    public Rectifier(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      gemv((Storage.DenseVector)_a, _w, _previous._a, _b, _dropout != null ? _dropout.bits() : null);
      final int rows = _a.size();
      for( int row = 0; row < rows; row++ ) {
        _a.set(row, Math.max(_a.get(row), 0f));
        compute_sparsity();
      }
    }

    @Override protected void bprop() {
      float m = momentum();
      float r = _minfo.adaDelta() ? 0 : rate(_minfo.get_processed_total()) * (1f - m);
      final int rows = _a.size();
      if (_w instanceof Storage.DenseRowMatrix) {
        for (int row = 0; row < rows; row++) {
          if (_minfo.get_params()._autoencoder && _index == _minfo.get_params()._hidden.length)
            _e.set(row, autoEncoderError(row));
          //(d/dx)(max(0,x)) = 1 if x > 0, otherwise 0
          float g = _a.get(row) > 0f ? _e.get(row) : 0f;
          bprop(row, g, r, m);
        }
      }
      else {
        bprop_sparse(r, m);
      }
    }
  }

  /**
   * Rectifier linear unit (ReLU) neurons with dropout
   */
  public static class RectifierDropout extends Rectifier {
    public RectifierDropout(int units) { super(units); }
    @Override protected void fprop(long seed, boolean training) {
      if (training) {
        seed += params._seed + 0x3C71F1ED;
        _dropout.fillBytes(seed);
        super.fprop(seed, true);
      }
      else {
        super.fprop(seed, false);
        ArrayUtils.mult(_a.raw(), (float)(1-params._hidden_dropout_ratios[_index]));
      }
    }
  }

  /**
   * Abstract class for Output neurons
   */
  public static abstract class Output extends Neurons {
    Output(int units) { super(units); }
    protected void fprop(long seed, boolean training) { throw new UnsupportedOperationException(); }
    protected void bprop() { throw new UnsupportedOperationException(); }
  }

  /**
   * Output neurons for classification - Softmax
   */
  public static class Softmax extends Output {
    public Softmax(int units) { super(units); }
    protected void fprop() {
      gemv((Storage.DenseVector) _a, (Storage.DenseRowMatrix) _w, (Storage.DenseVector) _previous._a, _b, null);
      final float max = ArrayUtils.maxValue(_a.raw());
      float scale = 0f;
      final float rows = _a.size();
      for( int row = 0; row < rows; row++ ) {
        _a.set(row, (float)Math.exp(_a.get(row) - max));
        scale += _a.get(row);
      }
      for( int row = 0; row < rows; row++ ) {
        _a.raw()[row] /= scale;
        if (Float.isNaN(_a.get(row))) {
          _minfo.set_unstable();
          throw new RuntimeException("Numerical instability, predicted NaN.");
        }
      }
    }

    /**
     * Backpropagation for classification
     * Update every weight as follows: w += -rate * dE/dw
     * Compute dE/dw via chain rule: dE/dw = dE/dy * dy/dnet * dnet/dw, where net = sum(xi*wi)+b and y = activation function
     * @param target actual class label
     */
    protected void bprop(int target) {
      assert (target != missing_int_value); // no correction of weights/biases for missing label
      float m = momentum();
      float r = _minfo.adaDelta() ? 0 : rate(_minfo.get_processed_total()) * (1f - m);
      float g; //partial derivative dE/dy * dy/dnet
      final float rows = _a.size();
      for( int row = 0; row < rows; row++ ) {
        final float t = (row == target ? 1f : 0f);
        final float y = _a.get(row);
        //dy/dnet = derivative of softmax = (1-y)*y
        if (params._loss == DeepLearningParameters.Loss.CrossEntropy) {
          //nothing else needed, -dCE/dy * dy/dnet = target - y
          //cf. http://www.stanford.edu/group/pdplab/pdphandbook/handbookch6.html
          g = t - y;
        } else if (params._loss == DeepLearningParameters.Loss.Absolute) {
          g = (2*t-1) * (1f - y) * y; //-dL/dy = 2*t-1
        } else if (params._loss == DeepLearningParameters.Loss.MeanSquare) {
          //-dMSE/dy = target-y
          g = (t - y) * (1f - y) * y;
        } else if (params._loss == DeepLearningParameters.Loss.Huber) {
          if (t==0) {
            if (y<0.5) {
              g = -4*y; //L=2*y^2 for y<0.5
            } else {
              g = -2;   //L=2*y-0.5 for y>=0.5
            }
          } else {
            if (y>0.5) {
              g = 4*(1-y); //L=2*(1-y)^2 for y<0.5
            } else {
              g = 2;   //L=2*(1-y)-0.5 for y>=0.5
            }
          }
          g *= (1f - y) * y;
        } else throw H2O.unimpl("Loss " + params._loss + " not implemented for classification.");
        // this call expects dE/dnet
        bprop(row, g, r, m);
      }
    }
  }

  /**
   * Output neurons for regression - Linear units
   */
  public static class Linear extends Output {
    public Linear(int units) { super(units); }
    protected void fprop() {
      gemv((Storage.DenseVector)_a, _w, _previous._a, _b, _dropout != null ? _dropout.bits() : null);
    }

    /**
     * Backpropagation for regression
     * @param target floating-point target value
     */
    protected void bprop(float target) {
      assert (target != missing_real_value);
      final int row = 0;
      final float t = target;
      final float y = _a.get(row);
      float g;
      // Computing partial derivative: dE/dnet = dE/dy * dy/dnet = dE/dy * 1
      if (params._loss == DeepLearningParameters.Loss.MeanSquare) {
        g = t - y; //for MSE -dMSE/dy = target-y
      }
      // L = |y-t|, -dL/dy = -/+1
      else if (params._loss == DeepLearningParameters.Loss.Absolute) {
        g = y > t ? -1f : 1f;
      }
      // Huber:
      // L = (y-t)^2    for |t-y| < 1,  -dL/dy = t - y
      // L = 2*|t-y|-1  for |t-y| >= 1, -dL/dy = +/- 2
      else if (params._loss == DeepLearningParameters.Loss.Huber) {
        if (Math.abs(y-t) < 1) {
          g = t - y;
        } else {
          g = y >= t + 1f ? -2f : 2f;
        }
      } else throw H2O.unimpl("Loss " + params._loss + " not implemented for regression.");
      float m = momentum();
      float r = _minfo.adaDelta() ? 0 : rate(_minfo.get_processed_total()) * (1f - m);
      bprop(row, g, r, m);
    }
  }

  /**
   * Mat-Vec Plus Add (with optional row dropout)
   * @param res = a*x+y (pre-allocated, will be overwritten)
   * @param a matrix of size rows x cols
   * @param x vector of length cols
   * @param y vector of length rows
   * @param row_bits if not null, check bits of this byte[] to determine whether a row is used or not
   */
  static void gemv_naive(final float[] res, final float[] a, final float[] x, final float[] y, byte[] row_bits) {
    final int cols = x.length;
    final int rows = y.length;
    assert(res.length == rows);
    for(int row = 0; row<rows; row++) {
      res[row] = 0;
      if( row_bits != null && (row_bits[row / 8] & (1 << (row % 8))) == 0) continue;
      for(int col = 0; col<cols; col++)
        res[row] += a[row*cols+col] * x[col];
      res[row] += y[row];
    }
  }

  /**
   * Optimized Mat-Vec Plus Add (with optional row dropout)
   * Optimization: Partial sums can be evaluated in parallel
   * @param res = a*x+y (pre-allocated, will be overwritten)
   * @param a matrix of size rows x cols
   * @param x vector of length cols
   * @param y vector of length rows
   * @param row_bits if not null, check bits of this byte[] to determine whether a row is used or not
   */
  static void gemv_row_optimized(final float[] res, final float[] a, final float[] x, final float[] y, final byte[] row_bits) {
    final int cols = x.length;
    final int rows = y.length;
    assert(res.length == rows);
    final int extra=cols-cols%8;
    final int multiple = (cols/8)*8-1;
    int idx = 0;
    for (int row = 0; row<rows; row++) {
      res[row] = 0;
      if( row_bits == null || (row_bits[row / 8] & (1 << (row % 8))) != 0) {
        float psum0 = 0, psum1 = 0, psum2 = 0, psum3 = 0, psum4 = 0, psum5 = 0, psum6 = 0, psum7 = 0;
        for (int col = 0; col < multiple; col += 8) {
          int off = idx + col;
          psum0 += a[off    ] * x[col    ];
          psum1 += a[off + 1] * x[col + 1];
          psum2 += a[off + 2] * x[col + 2];
          psum3 += a[off + 3] * x[col + 3];
          psum4 += a[off + 4] * x[col + 4];
          psum5 += a[off + 5] * x[col + 5];
          psum6 += a[off + 6] * x[col + 6];
          psum7 += a[off + 7] * x[col + 7];
        }
        res[row] += psum0 + psum1 + psum2 + psum3;
        res[row] += psum4 + psum5 + psum6 + psum7;
        for (int col = extra; col < cols; col++)
          res[row] += a[idx + col] * x[col];
        res[row] += y[row];
      }
      idx += cols;
    }
  }

  /**
   * Helper to do a generic gemv: res = a*x + y
   * @param res Dense result
   * @param a Matrix (sparse or dense)
   * @param x Vector (sparse or dense)
   * @param y Dense vector to add to result
   * @param row_bits Bit mask for which rows to use
   */
  static void gemv(final Storage.DenseVector res, final Storage.Matrix a, final Storage.Vector x, final Storage.DenseVector y, byte[] row_bits) {
    if (a instanceof Storage.DenseRowMatrix && x instanceof Storage.DenseVector)
      gemv(res, (Storage.DenseRowMatrix)a, (Storage.DenseVector)x, y, row_bits); //default
    else if (a instanceof Storage.DenseColMatrix && x instanceof Storage.SparseVector)
      gemv(res, (Storage.DenseColMatrix)a, (Storage.SparseVector)x, y, row_bits); //fast for really sparse
    else if (a instanceof Storage.DenseRowMatrix && x instanceof Storage.SparseVector)
      gemv(res, (Storage.DenseRowMatrix) a, (Storage.SparseVector) x, y, row_bits); //try
    else if (a instanceof Storage.DenseColMatrix && x instanceof Storage.DenseVector)
      gemv(res, (Storage.DenseColMatrix) a, (Storage.DenseVector) x, y, row_bits); //try
    else throw new UnsupportedOperationException("gemv for matrix " + a.getClass().getSimpleName() + " and vector + " + x.getClass().getSimpleName() + " not yet implemented.");
  }

  static void gemv(final Storage.DenseVector res, final Storage.DenseRowMatrix a, final Storage.DenseVector x, final Storage.DenseVector y, byte[] row_bits) {
    gemv_row_optimized(res.raw(), a.raw(), x.raw(), y.raw(), row_bits);
  }

  static void gemv_naive(final Storage.DenseVector res, final Storage.DenseRowMatrix a, final Storage.DenseVector x, final Storage.DenseVector y, byte[] row_bits) {
    gemv_naive(res.raw(), a.raw(), x.raw(), y.raw(), row_bits);
  }

  //TODO: make optimized version for col matrix
  static void gemv(final Storage.DenseVector res, final Storage.DenseColMatrix a, final Storage.DenseVector x, final Storage.DenseVector y, byte[] row_bits) {
    final int cols = x.size();
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
    }
    for(int c = 0; c<cols; c++) {
      final float val = x.get(c);
      for(int r = 0; r<rows; r++) {
        if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
        res.add(r, a.get(r,c) * val);
      }
    }
    for(int r = 0; r<rows; r++) {
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      res.add(r, y.get(r));
    }
  }

  static void gemv(final Storage.DenseVector res, final Storage.DenseRowMatrix a, final Storage.SparseVector x, final Storage.DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      int start = x.begin()._idx;
      int end = x.end()._idx;
      for (int it = start; it < end; ++it) {
        res.add(r, a.get(r, x._indices[it]) * x._values[it]);
      }
      res.add(r, y.get(r));
    }
  }

  static void gemv(final Storage.DenseVector res, final Storage.DenseColMatrix a, final Storage.SparseVector x, final Storage.DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
    }
    int start = x.begin()._idx;
    int end = x.end()._idx;
    for (int it = start; it < end; ++it) {
      final float val = x._values[it];
      if (val == 0f) continue;
      for(int r = 0; r<rows; r++) {
        if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
        res.add(r, a.get(r,x._indices[it]) * val);
      }
    }
    for(int r = 0; r<rows; r++) {
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      res.add(r, y.get(r));
    }
  }

  static void gemv(final Storage.DenseVector res, final Storage.SparseRowMatrix a, final Storage.SparseVector x, final Storage.DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      // iterate over all non-empty columns for this row
      TreeMap<Integer, Float> row = a.row(r);
      Set<Map.Entry<Integer,Float>> set = row.entrySet();

      for (Map.Entry<Integer,Float> e : set) {
        final float val = x.get(e.getKey());
        if (val != 0f) res.add(r, e.getValue() * val); //TODO: iterate over both iterators and only add where there are matching indices
      }
      res.add(r, y.get(r));
    }
  }

  static void gemv(final Storage.DenseVector res, final Storage.SparseColMatrix a, final Storage.SparseVector x, final Storage.DenseVector y, byte[] row_bits) {
    final int rows = y.size();
    assert(res.size() == rows);
    for(int r = 0; r<rows; r++) {
      res.set(r, 0);
    }
    for(int c = 0; c<a.cols(); c++) {
      TreeMap<Integer, Float> col = a.col(c);
      final float val = x.get(c);
      if (val == 0f) continue;
      for (Map.Entry<Integer,Float> e : col.entrySet()) {
        final int r = e.getKey();
        if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
        // iterate over all non-empty columns for this row
        res.add(r, e.getValue() * val);
      }
    }
    for(int r = 0; r<rows; r++) {
      if( row_bits != null && (row_bits[r / 8] & (1 << (r % 8))) == 0) continue;
      res.add(r, y.get(r));
    }
  }

}
