#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone.sh
JAR=$PWD/hot-knife-0.0.2-SNAPSHOT.jar
CLASS=org.janelia.saalfeldlab.hotknife.SparkGenerateFaceScaleSpace
N_NODES=10

N5PATH='/nrs/flyem/data/tmp/Z0115-22.n5'
N5DATASETINPUT='/slab-29/raw/s0'
N5GROUPOUTPUT='/slab-29/top'
MIN='0,26,0'
SIZE='0,512,0'
BLOCKSIZE='1024,1024'

ARGV="\
--n5Path '$N5PATH' \
--n5DatasetInput '$N5DATASETINPUT' \
--n5GroupOutput '$N5GROUPOUTPUT' \
--min '$MIN' \
--size '$SIZE' \
--blockSize '$BLOCKSIZE'"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
