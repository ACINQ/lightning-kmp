#!/bin/bash

docker exec eclair-node \
    ./eclair-cli -p foobar \
    $*
