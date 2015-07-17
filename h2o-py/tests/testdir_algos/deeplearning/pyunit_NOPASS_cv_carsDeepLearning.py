import sys
sys.path.insert(1, "../../../")
import h2o
import random

def cv_carsDL(ip,port):
    # Connect to h2o
    h2o.init(ip,port)

    # read in the dataset and construct training set (and validation set)
    cars =  h2o.import_frame(path=h2o.locate("smalldata/junit/cars_20mpg.csv"))

    # choose the type model-building exercise (multinomial classification or regression). 0:regression, 1:binomial,
    # 2:multinomial
    problem = random.sample(range(3),1)[0]

    # pick the predictors and the correct response column
    predictors = ["displacement","power","weight","acceleration","year"]
    if problem == 1   :
        response_col = "economy_20mpg"
        cars[response_col] = cars[response_col].asfactor()
    elif problem == 2 :
        response_col = "cylinders"
        cars[response_col] = cars[response_col].asfactor()
    else              :
        response_col = "economy"

    print "Response column: {0}".format(response_col)

    ## cross-validation
    ## basic
    nfolds = random.randint(3,10)
    dl = h2o.deeplearning(y=cars[response_col], x=cars[predictors], nfolds=nfolds)

    ## boundary case
    # nfolds = 0
    dl = h2o.deeplearning(y=cars[response_col], x=cars[predictors], nfolds=0)

    ## error cases
    # 1. nfolds == 1 or < 0
    # TODO: PUBDEV-1696
    try:
        dl = h2o.deeplearning(y=cars[response_col], x=cars[predictors], nfolds=random.randint(-10000,-1))
        dl = h2o.deeplearning(y=cars[response_col], x=cars[predictors], nfolds=1)
        assert False, "Expected model-build to fail when nfolds is 1 or < 0"
    except EnvironmentError:
        assert True

    # 2. cross-validation and regular validation attempted
    r = cars[0].runif()
    train = cars[r > .2]
    valid = cars[r <= .2]
    try:
        dl = h2o.deeplearning(y=train[response_col], x=train[predictors], nfolds=random.randint(3,10),
                               validation_y=valid[1], validation_x=valid[predictors])
        assert False, "Expected model-build to fail when both cross-validation and regular validation is attempted"
    except EnvironmentError:
        assert True

    # TODO: what should the model metrics look like? add cross-validation metric check to pyunit_metric_json_check.

if __name__ == "__main__":
    h2o.run_test(sys.argv, cv_carsDL)