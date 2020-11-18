#!/usr/bin/env bash

basedir=$(dirname ${BASH_SOURCE[0]})

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
    $basedir/gen-blocks.sh 150
    docker stop -t 2 bitcoind
}

function btc_start {
    docker start bitcoind
}

function btc_logs {
    docker logs bitcoind -f
}

function btc_stop {
    docker stop -t 2 bitcoind
}

function btc_remove {
    docker rm bitcoind
}

function elx_create {
    docker build \
        -t electrumx \
        $basedir/electrumx

    docker create \
        --name electrumx \
        --net eclair-net \
        -e DAEMON_URL=http://foo:bar@bitcoind:18443 \
        -e COIN=BitcoinSegwit \
        -e NET=regtest \
        -e LOG_LEVEL=debug \
        -p 51001:50001 \
        -p 51002:50002 \
        electrumx
}

function elx_start {
    docker start electrumx
}

function elx_logs {
    docker logs electrumx -f
}

function elx_stop {
    docker stop -t 2 electrumx
}

function elx_remove {
    docker rm electrumx
}

function ecl_create {
    docker build \
        -t eclair-node \
        -f $basedir/eclair/node.Dockerfile \
        $basedir/eclair

    docker create \
        --name eclair-nodeA \
        --net eclair-net \
        -v "$PWD/../build/logs":/logs \
        -e NODE_NAME=nodeA \
        -p 48001:9735 \
        -p 8081:8080 \
        eclair-node

    docker create \
        --name eclair-nodeB \
        --net eclair-net \
        -v "$PWD/../build/logs":/logs \
        -e NODE_NAME=nodeB \
        -p 48002:9735 \
        -p 8082:8080 \
        eclair-node
}

function ecl_start {
    docker start eclair-nodeA
    docker start eclair-nodeB
}

function ecl_connect {
    # B connects with A
    docker exec eclair-nodeB ./eclair-cli -p foobar connect --uri=039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585@eclair-nodeA:9735
}

function ecl_open {
    # B opens a channel with A (must be connected first)
    docker exec eclair-nodeB ./eclair-cli -p foobar open --nodeId=039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585 --fundingSatoshis=200000
}

function ecl_logs_a {
    docker logs eclair-nodeA -f
}

function ecl_logs_b {
    docker logs eclair-nodeB -f
}

function ecl_stop {
    docker stop -t 2 eclair-nodeA
    docker stop -t 2 eclair-nodeB
}

function ecl_remove {
    docker rm eclair-nodeA
    docker rm eclair-nodeB
}

function ecl_gui_create {
    if [ "$(uname)" = "Linux" ]; then
        displayAddr=unix$DISPLAY
        GID=$(id -g)
    elif [ "$(uname)" = "Darwin" ]; then
        displayAddr=host.docker.internal:0
        GID=$(id -u)
    else
        echo "Unsupported OS: $(uname)"
        exit 1
    fi

    docker build \
        --build-arg user=$USER \
        --build-arg uid=$(id -u) \
        --build-arg gid=$GID \
        -t eclair-gui \
        -f $basedir/eclair/gui.Dockerfile \
        $basedir/eclair

    docker create \
        --name eclair-gui \
        --net eclair-net \
        -e DISPLAY=$displayAddr \
        -v /tmp/.X11-unix:/tmp/.X11-unix \
        -p 48001:48001 \
        -it eclair-gui
}

function ecl_gui_run {
    if [ "$(uname)" = "Darwin" ]; then
        xhost + 127.0.0.1
    fi

    docker start -a eclair-gui
}

function ecl_gui_remove {
    docker rm eclair-gui
}

function show_help {
    echo "Commands:"
    echo ""
    echo "  net-create      Creates network"
    echo "  net-remove      Removes network (needs all containers to be removed)"
    echo ""
    echo "  btc-create      Creates and configure Bitcoind (needs network to be created)"
    echo "  btc-start       Starts Bitcoind"
    echo "  btc-logs        Shows Bitcoind logs"
    echo "  btc-stop        Stops Bitcoind"
    echo "  btc-remove      Removes Bitcoind"
    echo ""
    echo "  elx-create      Builds and creates ElectrumX (needs network to be created)"
    echo "  elx-start       Starts ElectrumX"
    echo "  elx-logs        Shows ElectrumX logs"
    echo "  elx-stop        Stops ElectrumX"
    echo "  elx-remove      Removes ElectrumX"
    echo ""
    echo "  ecl-create      Builds and creates Eclair nodes (without GUI, needs network to be created)"
    echo "  ecl-start       Starts Eclair nodes"
    echo "  ecl-connect     Connects Eclair nodes"
    echo "  ecl-open        Opens channels between Eclair nodes (they must be connected first)"
    echo "  ecl-logs-a      Shows Eclair logs for node A"
    echo "  ecl-logs-b      Shows Eclair logs for node B"
    echo "  ecl-stop        Stops Eclair nodes"
    echo "  ecl-remove      Removes Eclair nodes"
    echo ""
    echo "  ecl-gui-create  Builds and creates Eclair with GUI (needs network to be created)"
    echo "  ecl-gui-run     Runs Eclair with GUI (attaches to the console)"
    echo "  ecl-gui-remove  Removes Eclair-UI"
    echo ""
    echo "  help            Shows this help"
    echo ""
    echo ""
    echo "Shortcuts:"
    echo ""
    echo "  create          net-create btc-create elx-create ecl-create"
    echo "  create-gui      net-create btc-create elx-create ecl-gui-create"
    echo "  remove          ecl-remove ecl-gui-remove elx-remove btc-remove net-remove"
    echo ""
    echo "  start           btc-start elx-start ecl-start"
    echo "  clean-start     remove create start"
    echo "  stop            ecl-stop elx-stop btc-stop"
    echo ""
    echo "  run-gui         btc-start elx-start ecl-gui-run elx-stop btc-stop"
    echo "  clean-run-gui   remove create-gui run-gui"
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
            btc-logs)
                btc_logs
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
            elx-logs)
                elx_logs
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
            ecl-start)
                ecl_start
                ;;
            ecl-connect)
                ecl_connect
                ;;
            ecl-open)
                ecl_open
                ;;
            ecl-logs-a)
                ecl_logs_a
                ;;
            ecl-logs-b)
                ecl_logs_b
                ;;
            ecl-stop)
                ecl_stop
                ;;
            ecl-remove)
                ecl_remove
                ;;
            ecl-gui-create)
                ecl_gui_create
                ;;
            ecl-gui-run)
                ecl_gui_run
                ;;
            ecl-gui-remove)
                ecl_gui_remove
                ;;
            help)
                show_help
                ;;

            create)
                cmd net-create btc-create elx-create ecl-create
                ;;
            create-gui)
                cmd net-create btc-create elx-create ecl-gui-create
                ;;
            remove)
                cmd ecl-remove ecl-gui-remove elx-remove btc-remove net-remove
                ;;
            start)
                cmd btc-start elx-start ecl-start
                ;;
            clean-start)
                cmd remove create start
                ;;
            stop)
                cmd ecl-stop elx-stop btc-stop
                ;;
            run-gui)
                cmd btc-start elx-start ecl-gui-run elx-stop btc-stop
                ;;
            clean-run-gui)
                cmd remove create-gui run-gui
                ;;

            *)
                echo "Unknown command $1"
                show_help
                ;;
        esac
    done
}
