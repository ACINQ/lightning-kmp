# Manual tests with Docker

`eclair-kmp` includes a simple `Node` test program that simulates a Phoenix can and can be used to test against local eclair nodes. To run it just start `Node` in the `jvmTest` package. It will start an `eclair-kmp` node, connect it to a
hardcoded `eclair` node (that must already be running)
and read and parse text inputs from Idea's console.

Tests use a small docker environment:

- a shared `eclair-net` docker network
- an electrumx container
- a bitcoind container
- 2 eclair containers, nodes A and B

For testing, A simulates horizon/endurance and B simulates a node we want to exchange payments with.

```
 B <--> A <--> eclair-kmp

```

## Create all containers, and their shared network

```
$ ./env.sh net-create btc-create elx-create ecl-create
```

Check that all containers exists with `docker ps -a`: you should see something like:

```
CONTAINER ID        IMAGE                            COMMAND                  CREATED             STATUS                      PORTS               NAMES
ada51b9a9dab        eclair-node                      "/bin/sh -c './eclai…"   50 seconds ago      Created                                         eclair-nodeB
318055ebab55        eclair-node                      "/bin/sh -c './eclai…"   50 seconds ago      Created                                         eclair-nodeA
b06bc1a1ea57        electrumx                        "init"                   51 seconds ago      Created                                         electrumx
7f0f2997f328        ruimarinho/bitcoin-core:latest   "/entrypoint.sh -pri…"   54 seconds ago      Exited (0) 51 seconds ago                       bitcoind
```

## Start bitcoind and electrumx

```
$ ./env.sh btc-start elx-start
```

You can check that they're running with `./env.sh btc-logs` and `./env.sh elx-logs`

## Start eclair nodes

```
$ ./env.sh ecl-start 
```

You can check that they're running properly and start using their APIss:

```
$ docker exec eclair-nodeA ./eclair-cli -p foobar getinfo
$ docker exec eclair-nodeB ./eclair-cli -p foobar getinfo
```

## Connect node B to node A

```
$ docker exec eclair-nodeB ./eclair-cli -p foobar connect --uri=039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585@eclair-nodeA:9735
$ docker exec eclair-nodeB ./eclair-cli -p foobar open --nodeId=039dc0e0b1d25905e44fdf6f8e89755a5e219685840d0bc1d28d3308f9628a3585 --fundingSatoshis=200000 --pushMsat=50000000
```

## Start eclair-kmp

From Idea, start the `Node` class in `jvmTest`. It should automatically connect to node A. Get your eclair-kmp node Id from the console logs and open a channel from node A:

```
docker exec eclair-nodeA ./eclair-cli -p foobar open --nodeId=03af0ed6052cf28d670665549bc86f4b721c9fdb309d40c58f5811f63966e005d0 --fundingSatoshis=200000 --pushMsat=50000000
```

## Confirm pending channels

```
$ ./gen-blocks.sh 3
```

node A should now have 2 channels in `NORMAL` state:

```
docker exec $ eclair-nodeA ./eclair-cli -p foobar channels
```

## Send a payment from eclair-kmp to node B

Generate a payment request on node B:

```
docker exec eclair-nodeB ./eclair-cli -p foobar createinvoice --description=test --amountMsat=50000
```

In the Idea console, type `pay` and paste the generated payment request