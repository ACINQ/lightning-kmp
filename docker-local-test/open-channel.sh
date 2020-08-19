#!/bin/bash

docker exec eclair \
    ./eclair-node-gui/bin/eclair-cli -p foobar \
    open --nodeId=03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0 \
    --fundingSatoshis=200000 --pushMsat=50000000

sleep 1

./gen-blocks.sh 5
