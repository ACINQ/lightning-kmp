#!/usr/bin/env bash
docker network create \
  --driver=bridge \
  --subnet=172.20.0.0/16 \
  --ip-range=172.20.0.0/24 \
  --gateway=172.20.0.254 \
  eklair-net

docker run --rm -d \
  --name bitcoind \
  --net eklair-net \
  -p 18443:18443 \
  -p 18444:18444 \
  -p 29000:29000 \
  -p 29001:29001 \
  ruimarinho/bitcoin-core:latest \
  -printtoconsole \
  -regtest=1 \
  -server=1 \
  -rpcuser=foo \
  -rpcpassword=bar \
  -txindex=1 \
  -fallbackfee=0.0002 \
  -rpcallowip=172.20.0.0/16 \
  -rpcbind=0.0.0.0 \
  -zmqpubrawblock=tcp://0.0.0.0:29000 \
  -zmqpubrawtx=tcp://0.0.0.0:29001

docker run --rm -d \
  --name electrumx \
  --net eklair-net \
  -e DAEMON_URL=http://foo:bar@bitcoind:18443 \
  -e COIN=BitcoinSegwit \
  -e NET=regtest \
  -p 51001:50001 \
  -p 51002:50002 \
  acinq/electrumx