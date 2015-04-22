setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source("../h2o-runit.R")

#------------------#
# Testing on HTTPS #
#------------------#

test.import.https <- function(conn) {

  url <- "https://s3.amazonaws.com/h2o-public-test-data/smalldata/prostate/prostate.csv.zip"

  t <- system.time(aa <- h2o.importFile(conn, url))
  print(aa)
  print(t)

  testEnd()
}

doTest("Testing HTTPS File Import", test.import.https)