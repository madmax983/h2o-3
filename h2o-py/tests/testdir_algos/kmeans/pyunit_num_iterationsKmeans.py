import sys
sys.path.insert(1, "../../../")
import h2o

def km_num_iterations(ip,port):
    # Connect to a pre-existing cluster
    h2o.init(ip,port)  # connect to localhost:54321

    prostate_h2o = h2o.import_frame(path=h2o.locate("smalldata/logreg/prostate.csv"))

    prostate_km_h2o = h2o.kmeans(x=prostate_h2o[1:], k=3, max_iterations=2)
    num_iterations = prostate_km_h2o.num_iterations()
    #TODO: is there and off-by-one error here?
    assert num_iterations <= 4, "Expected 4 iterations, but got {0}".format(num_iterations)

if __name__ == "__main__":
    h2o.run_test(sys.argv, km_num_iterations)
