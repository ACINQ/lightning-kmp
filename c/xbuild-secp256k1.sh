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

if [[ $HOST == "linux" ]];
then
  echo "We can't cross build for ios targets using a linux host !";
  echo "Only doing a classic linux build";
  cd secp256k1
  ./configure --enable-experimental --enable-module_ecdh
  make
  mkdir build/linux/
  cp .libs/* build/linux/
fi;

if [[ $HOST == "mac" ]];
then
#  if [[ -z $(which iconfigure) ]];
#  then
#    echo "You need to have iconfigure in your PATH : "
#  fi;
  cp xconfigure.sh secp256k1
  cd secp256k1
  sh xconfigure.sh --enable-experimental --enable-module_ecdh --enable-benchmark=no
  echo "Copying result to build/ios"
  mkdir build/ios
  cp _build/universal/* build/ios/
fi

