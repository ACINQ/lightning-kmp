#!/bin/bash

if [ "$(uname)" = "Linux" ]; then
    displayAddr=unix$DISPLAY
    GID=$(id -g)
elif [ "$(uname)" = "Darwin" ]; then
    displayAddr=host.docker.internal:0
    xhost + 127.0.0.1
    GID=$(id -u)
else
    echo "Unsupported OS: $(uname)"
    exit 1
fi

../docker-env.sh

sleep 2

./gen-blocks.sh 150

docker build \
    --build-arg user=$USER \
    --build-arg uid=$(id -u) \
    --build-arg gid=$GID \
    -t eclair-gui eclair-gui

docker run --rm \
    --name eclair \
    --net eklair-net \
    -e DISPLAY=$displayAddr \
    -v /tmp/.X11-unix:/tmp/.X11-unix \
    -p 48001:48001 \
    -it eclair-gui

../docker-cleanup.sh
