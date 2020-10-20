#!/bin/bash

basedir=$(dirname ${BASH_SOURCE[0]})

$basedir/bitcoind-cli.sh generatetoaddress $1 $($basedir/bitcoind-cli.sh getnewaddress)
