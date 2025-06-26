#!/bin/bash -x
#
# first you must sign all files with something like:
# find release -type f -print -exec gpg -ab {} \;
#

if [[ -z "${VERSION}" ]]; then
  echo "VERSION is not defined"
  exit 1
fi

if [[ -z "${CENTRAL_TOKEN_GPG_FILE}" ]]; then
  echo "CENTRAL_TOKEN_GPG_FILE is not defined"
  exit 1
fi

CENTRAL_TOKEN=$(gpg --decrypt $CENTRAL_TOKEN_GPG_FILE)

pushd .
cd release
for i in lightning-kmp-core \
		lightning-kmp-core-iosarm64 \
		lightning-kmp-core-iossimulatorarm64 \
		lightning-kmp-core-iosx64 \
		lightning-kmp-core-jvm \
		lightning-kmp-core-linuxarm64 \
		lightning-kmp-core-linuxx64 \
		lightning-kmp-core-macosarm64 \
		lightning-kmp-core-macosx64
do
	DIR=fr/acinq/lightning/$i/$VERSION
	case $1 in
	  create)
  	  for file in $DIR/*
	    do
	      sha1sum $file | sed 's/ .*//' > $file.sha1
	      md5sum $file | sed 's/ .*//' > $file.md5
	      gpg -ab $file
      done
	    zip -r $i.zip $DIR
	  ;;
  	upload)
	    curl --request POST --verbose --header "Authorization: Bearer ${CENTRAL_TOKEN}" --form bundle=@$i.zip https://central.sonatype.com/api/v1/publisher/upload
    ;;
  esac
done
popd
