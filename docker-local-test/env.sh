#!/usr/bin/env bash

if [ "$(uname)" = "Linux" ]; then
    displayAddr=unix$DISPLAY
    preUICommand=""
    GID=$(id -g)
elif [ "$(uname)" = "Darwin" ]; then
    displayAddr=host.docker.internal:0
    preUICommand="xhost + 127.0.0.1"
    GID=$(id -u)
else
    echo "Unsupported OS: $(uname)"
    exit 1
fi

function create_network {
  docker network create \
    --driver=bridge \
    --subnet=172.20.0.0/16 \
    --ip-range=172.20.0.0/24 \
    --gateway=172.20.0.254 \
    eklair-net
}

function create_containers {
  docker create \
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

  docker start bitcoind
  sleep 2
  ./gen-blocks.sh 150
  docker stop bitcoind

  docker create \
    --name electrumx \
    --net eklair-net \
    -e DAEMON_URL=http://foo:bar@bitcoind:18443 \
    -e COIN=BitcoinSegwit \
    -e NET=regtest \
    -p 51001:50001 \
    -p 51002:50002 \
    acinq/electrumx

  docker build \
    --build-arg user=$USER \
    --build-arg uid=$(id -u) \
    --build-arg gid=$GID \
    -t eclair-gui eclair-gui

  docker create \
    --name eclair \
    --net eklair-net \
    -e DISPLAY=$displayAddr \
    -v /tmp/.X11-unix:/tmp/.X11-unix \
    -p 48001:48001 \
    -it eclair-gui
}

function start_background() {
  docker start bitcoind electrumx
}

function stop_background() {
  docker stop electrumx bitcoind
}

function run_ui() {
  ${preUICommand}
  docker start -a eclair
}

function remove_containers() {
  docker rm electrumx bitcoind eclair
}

function remove_network() {
  docker network rm eklair-net
}

function show_help() {
  echo "Valid commands:"
  echo "  create     Creates and configure all 3 containers (Bitcoind, ElectrumX & Eclair)"
  echo "  bgstart    Starts background service containers (Bitcoind & ElectrumX)"
  echo "  bgstop     Stops background service containers (Bitcoind & ElectrumX)"
  echo "  ui         Starts UI container (Eclair)"
  echo "  clean      Removes all 3 containers (Bitcoind, ElectrumX & Eclair)"
  echo "  run-once   Equivalent to 'clean create bgstart ui bgstop clean'"
}

function run() {
  for i in "$@"
  do
    case $i in
      create)
        create_network
        create_containers
        ;;
      bgstart)
        start_background
        ;;
      bgstop)
        stop_background
        ;;
      ui)
        run_ui
        ;;
      clean)
        remove_containers
        remove_network
        ;;
      run-once)
        run clean create bgstart ui bgstop clean
        ;;
      help)
        show_help
        ;;
      *)
        echo "Unknown command $1"
        show_help
        ;;
    esac
  done
}

if [ "$1" == "" ]; then
  show_help
else
  run $*
fi
