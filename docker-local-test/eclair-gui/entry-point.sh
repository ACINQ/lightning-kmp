#!/bin/bash

SERVER_PORT=48001

BITCOIND_HOST=127.0.0.1
BITCOIND_RPC_PORT=18443
BITCOIND_RPC_USER=foo
BITCOIND_RPC_PASSWORD=bar
BITCOIND_ZMQ_BLOCK=tcp://127.0.0.1:29000
BITCOIND_ZMQ_TX=tcp://127.0.0.1:29001

API_PORT=8080
API_ENABLED=true
API_PASSWORD=foobar

for i in "$@"
do
case $i in
    -server-port=*)
    SERVER_PORT="${i#*=}"
    shift
    ;;
    -bitcoind-host=*)
    BITCOIND_HOST="${i#*=}"
    shift
    ;;
    -bitcoind-rpc-port=*)
    BITCOIND_RPC_PORT="${i#*=}"
    shift
    ;;
    -bitcoind-rpc-user=*)
    BITCOIND_RPC_USER="${i#*=}"
    shift
    ;;
    -bitcoind-rpc-password=*)
    BITCOIND_RPC_PASSWORD="${i#*=}"
    shift
    ;;
    -bitcoind-zmq-block=*)
    BITCOIND_ZMQ_BLOCK="${i#*=}"
    shift
    ;;
    -bitcoind-zmq-tx=*)
    BITCOIND_ZMQ_TX="${i#*=}"
    shift
    ;;
    -api-port=*)
    API_PORT="${i#*=}"
    shift
    ;;
    -api-enabled=*)
    API_ENABLED="${i#*=}"
    shift
    ;;
    -api-password=*)
    API_PASSWORD="${i#*=}"
    shift
    ;;
    *)
		echo "Unknown option $i"
		exit 1
    ;;
esac
done

cat <<EOF > ~/.eclair/eclair.conf
eclair {
	chain = regtest
	server {
		port = $SERVER_PORT
	}
	bitcoind {
		host = "$BITCOIND_HOST"
		rpcport = $BITCOIND_RPC_PORT
		rpcuser = "$BITCOIND_RPC_USER"
		rpcpassword = "$BITCOIND_RPC_PASSWORD"
		zmqblock = "$BITCOIND_ZMQ_BLOCK"
		zmqtx = "$BITCOIND_ZMQ_TX"
	}
	api {
		port = $API_PORT
		enabled = $API_ENABLED
		password = $API_PASSWORD
	}
}
EOF

cat ~/.eclair/eclair.conf

./eclair-node-gui/bin/eclair-node-gui.sh -Declair.printToConsole
