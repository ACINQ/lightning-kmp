#!/bin/bash

if [ "$1" == "" ]; then
    echo "No node ID argument"
    exit 1
fi

docker exec eclair \
    ./eclair-node-gui/bin/eclair-cli -p foobar \
    open --nodeId=$1 \
    --fundingSatoshis=200000 --pushMsat=50000000

sleep 1

./gen-blocks.sh 5
