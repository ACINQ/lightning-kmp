#!/usr/bin/env bash

source cmd.inc.sh

if [ "$1" == "" ]; then
    show_help
else
    cmd $*
fi
