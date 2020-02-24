#!/bin/bash

OWN_DIR=`dirname "${BASH_SOURCE[0]}"`
ABS_DIR=`readlink -f "$OWN_DIR"`

FLINTSTONE=$ABS_DIR/flintstone/flintstone-lsd.sh
JAR=$PWD/hot-knife-0.0.4-SNAPSHOT.jar # this jar must be accessible from the cluster
CLASS=org.janelia.saalfeldlab.hotknife.SparkExportFlattenedVolume
N_NODES=10

SLAB_ID=21
RAW="/zcorr/Sec21___20200106_084220"
FIELD="/heightfields/Sec21_20200207_112747_s1_sp0"

ARGV="\
--n5RawPath=/nrs/flyem/tmp/VNC.n5 \
--n5FieldPath=/nrs/flyem/tmp/VNC.n5 \
--n5OutputPath=/nrs/flyem/tmp/VNC-align.n5 \
--n5RawDataset=$RAW/s0 \
--n5FieldGroup=$FIELD \
--n5OutDataset=/align/slab-$SLAB_ID/raw/s0 \
--padding=20 \
--blockSize=128,128,128"

TERMINATE=1 $FLINTSTONE $N_NODES $JAR $CLASS $ARGV
