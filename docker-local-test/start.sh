#!/bin/bash

../docker-env.sh

sleep 2

./genblocks.sh 150

docker build -t eclair-gui eclair-gui

xhost + 127.0.0.1

docker run --rm \
    --name eclair \
    --net eklair-net \
    -e DISPLAY=host.docker.internal:0 \
    -v /tmp/.X11-unix:/tmp/.X11-unix \
    -p 48001:48001 \
    -it eclair-gui

../docker-cleanup.sh
