#!/bin/bash

if [ "$(uname)" = "Linux" ]; then
    dockDisplay=unix:0
    diplayHost=local:root
elif [ "$(uname)" = "Darwin" ]; then
    dockDisplay=host.docker.internal:0
    diplayHost=127.0.0.1
else
    echo "Unsupported OS: $(uname)"
    exit 1
fi

../docker-env.sh

sleep 2

./genblocks.sh 150

docker build -t eclair-gui eclair-gui

xhost + $diplayHost

docker run --rm \
    --name eclair \
    --net eklair-net \
    -e DISPLAY=$dockDisplay \
    -v /tmp/.X11-unix:/tmp/.X11-unix \
    -p 48001:48001 \
    -it eclair-gui

xhost - $diplayHost

../docker-cleanup.sh
