#!/bin/bash

../docker-env.sh

sleep 2

./genblocks.sh 150

docker build -t eclair-gui eclair-gui

ip=$(ifconfig en0 | grep inet | awk '$1=="inet" {print $2}')
xhost + $ip

docker run --rm \
    --name eclair \
    --net eklair-net \
    -e DISPLAY=$ip:0 \
    -v /tmp/.X11-unix:/tmp/.X11-unix \
    -p 48001:48001 \
    eclair-gui

../docker-cleanup.sh
