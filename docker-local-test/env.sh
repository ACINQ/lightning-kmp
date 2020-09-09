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

function net_create {
  docker network create \
    --driver=bridge \
    --subnet=172.20.0.0/16 \
    --ip-range=172.20.0.0/24 \
    --gateway=172.20.0.254 \
    eclair-net
}

function net_remove {
  docker network rm eclair-net
}

function btc_create {
  docker create \
    --name bitcoind \
    --net eclair-net \
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
}

function btc_start {
  docker start bitcoind
}

function btc_stop {
  docker stop bitcoind
}

function btc_remove {
  docker rm bitcoind
}

function elx_create {
  docker create \
    --name electrumx \
    --net eclair-net \
    -e DAEMON_URL=http://foo:bar@bitcoind:18443 \
    -e COIN=BitcoinSegwit \
    -e NET=regtest \
    -p 51001:50001 \
    -p 51002:50002 \
    acinq/electrumx
}

function elx_start {
  docker start electrumx
}

function elx_stop {
  docker stop electrumx
}

function elx_remove {
  docker rm electrumx
}

function ecl_create {
  docker build \
    --build-arg user=$USER \
    --build-arg uid=$(id -u) \
    --build-arg gid=$GID \
    -t eclair-gui eclair-gui

  docker create \
    --name eclair \
    --net eclair-net \
    -e DISPLAY=$displayAddr \
    -v /tmp/.X11-unix:/tmp/.X11-unix \
    -p 48001:48001 \
    -it eclair-gui
}

function ecl_run {
  ${preUICommand}
  docker start -a eclair
}

function ecl_remove {
  docker rm eclair
}

function show_help {
  echo "Commands:"
  echo ""
  echo "  net-create  Creates network"
  echo "  net-remove  Removes network (needs all containers to be removed)"
  echo ""
  echo "  btc-create  Creates and configure Bitcoind (needs network to be created)"
  echo "  btc-start   Starts Bitcoind"
  echo "  btc-stop    Stops Bitcoind"
  echo "  btc-remove  Removes Bitcoind"
  echo ""
  echo "  elx-create  Creates ElectrumX (needs network to be created)"
  echo "  elx-start   Starts ElectrumX"
  echo "  elx-stop    Stops ElectrumX"
  echo "  elx-remove  Removes ElectrumX"
  echo ""
  echo "  ecl-create  Builds and creates Eclair-UI (needs network to be created)"
  echo "  ecl-run     Runs Eclair-UI attached to the console"
  echo "  ecl-remove  Removes Eclair-UI"
  echo ""
  echo "  help        Shows this help"
  echo ""
  echo ""
  echo "Shortcuts:"
  echo ""
  echo "  all-create  net-create btc-create elx-create ecl-create"
  echo "  all-remove  ecl-remove elx-remove btc-remove net-remove"
  echo ""
  echo "  run         btc-start elx-start ecl-run elx-stop btc-stop"
  echo "  clean-run   all-remove all-create run"
}

function cmd {
  for i in "$@"
  do
    case $i in
      net-create)
        net_create
        ;;
      net-remove)
        net_remove
        ;;
      btc-create)
        btc_create
        ;;
      btc-start)
        btc_start
        ;;
      btc-stop)
        btc_stop
        ;;
      btc-remove)
        btc_remove
        ;;
      elx-create)
        elx_create
        ;;
      elx-start)
        elx_start
        ;;
      elx-stop)
        elx_stop
        ;;
      elx-remove)
        elx_remove
        ;;
      ecl-create)
        ecl_create
        ;;
      ecl-run)
        ecl_run
        ;;
      ecl-remove)
        ecl_remove
        ;;
      help)
        show_help
        ;;

      all-create)
        cmd net-create btc-create elx-create ecl-create
        ;;
      all-remove)
        cmd ecl-remove elx-remove btc-remove net-remove
        ;;
      run)
        cmd btc-start elx-start ecl-run elx-stop btc-stop
        ;;
      clean-run)
        cmd all-remove all-create run
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
  cmd $*
fi
