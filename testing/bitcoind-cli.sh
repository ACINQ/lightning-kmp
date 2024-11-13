#!/bin/bash

docker exec bitcoind \
    bitcoin-cli -regtest \
    -rpcuser=foo -rpcpassword=bar \
    $*
