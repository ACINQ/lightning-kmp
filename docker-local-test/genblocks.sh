#!/bin/bash

docker exec bitcoind bitcoin-cli -regtest -rpcuser=foo -rpcpassword=bar generatetoaddress $1 `docker exec bitcoind bitcoin-cli -regtest -rpcuser=foo -rpcpassword=bar getnewaddress`
