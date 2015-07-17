package hex.schemas;

import hex.pca.PCAModel;
import water.api.*;

public class PCAModelV99 extends ModelSchema<PCAModel, PCAModelV99, PCAModel.PCAParameters, PCAV99.PCAParametersV99, PCAModel.PCAOutput, PCAModelV99.PCAModelOutputV99> {
  public static final class PCAModelOutputV99 extends ModelOutputSchema<PCAModel.PCAOutput, PCAModelOutputV99> {
    // Output fields; input fields are in the parameters list
    // TODO: This field is redundant. Remove in next API change.
    @API(help = "Standard deviations")
    public double[] std_deviation;

    @API(help = "Importance of each principal component")
    public TwoDimTableBase pc_importance;

    @API(help = "Principal components matrix")
    public TwoDimTableBase eigenvectors;

    @API(help = "Frame key for loading matrix (Power method only)")
    public KeyV3.FrameKeyV3 loading_key;

    @API(help = "GLRM final value of L2 loss function")
    public double objective;
  }

  // TODO: I think we can implement the following two in ModelSchema, using reflection on the type parameters.
  public PCAV99.PCAParametersV99 createParametersSchema() { return new PCAV99.PCAParametersV99(); }
  public PCAModelOutputV99 createOutputSchema() { return new PCAModelOutputV99(); }

  // Version&Schema-specific filling into the impl
  @Override public PCAModel createImpl() {
    PCAModel.PCAParameters parms = parameters.createImpl();
    return new PCAModel( model_id.key(), parms, null );
  }
}
