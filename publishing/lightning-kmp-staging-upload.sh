#!/bin/bash -x
#
# first you must sign all files with something like:
# find release -type f -print -exec gpg -ab {} \;
#

if [[ -z "${VERSION}" ]]; then
  echo "VERSION is not defined"
  exit 1
fi

if [[ -z "${OSS_USER}" ]]; then
  echo "OSS_USER is not defined"
  exit 1
fi

read -p "Password : " -s OSS_PASSWORD

for i in 	lightning-kmp \
		lightning-kmp-iosarm64 \
		lightning-kmp-iosx64 \
		lightning-kmp-jvm \
		lightning-kmp-linux
do
	pushd .
	cd release/fr/acinq/lightning/$i/$VERSION &&\
	pwd &&\
	jar -cvf bundle.jar *&&\
	curl -v -XPOST -u $OSS_USER:$OSS_PASSWORD --upload-file bundle.jar https://oss.sonatype.org/service/local/staging/bundle_upload	
  popd
done
