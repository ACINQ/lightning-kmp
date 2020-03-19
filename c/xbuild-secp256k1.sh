#!/bin/bash
set -e

if [[ $(uname -s) == "Darwin" ]];
then
  HOST="mac";
else
  HOST="linux";
fi;

rm -rf secp256k1/build
mkdir secp256k1/build
CONFIGURE_OPTS="--enable-experimental --enable-module_ecdh --enable-benchmark=no --enable-shared=no --enable-exhaustive-tests=no --enable-tests=no"
if [[ $HOST == "linux" ]];
then
  echo "We can't cross build for ios targets using a linux host !";
  echo "Only doing a classic linux build";
  cd secp256k1
  ./configure $CONFIGURE_OPTS
  make clean
  make
  mkdir build/linux/
  cp .libs/* build/linux/
fi;

if [[ $HOST == "mac" ]];
then
  cp xconfigure.sh secp256k1
  cd secp256k1
  sh xconfigure.sh $CONFIGURE_OPTS
  echo "Copying result to build/ios"
  mkdir build/ios
  cp _build/universal/* build/ios/
  rm -rf _build/

  echo "Cross building linux x64 version"
  ./configure --host x86_64-pc-linux $CONFIGURE_OPTS
  make clean
  make
  mkdir build/linux
  cp .libs/* build/linux/
fi

make clean
