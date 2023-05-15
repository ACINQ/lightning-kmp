#!/bin/bash -x

GROUP_ID=fr.acinq.lightning
ARTIFACT_ID_BASE=lightning-kmp

if [[ -z "${VERSION}" ]]; then
  echo "VERSION is not defined"
  exit 1
fi

cd snapshot
pushd .
cd fr/acinq/lightning/lightning-kmp/$VERSION
mvn deploy:deploy-file -DrepositoryId=ossrh -Durl=https://oss.sonatype.org/content/repositories/snapshots/ \
  -DpomFile=$ARTIFACT_ID_BASE-$VERSION.pom \
  -Dfile=$ARTIFACT_ID_BASE-$VERSION.jar \
  -Dfiles=$ARTIFACT_ID_BASE-$VERSION.module,$ARTIFACT_ID_BASE-$VERSION-kotlin-tooling-metadata.json \
  -Dtypes=module,json \
  -Dclassifiers=,kotlin-tooling-metadata \
  -Dsources=$ARTIFACT_ID_BASE-$VERSION-sources.jar \
  -Djavadoc=$ARTIFACT_ID_BASE-$VERSION-javadoc.jar
popd
pushd .
for i in iosarm64 iosx64 jvm linux; do
  cd fr/acinq/lightning/lightning-kmp-$i/$VERSION
  if [ $i == iosarm64 ] || [ $i == iosx64 ]; then
    mvn deploy:deploy-file -DrepositoryId=ossrh -Durl=https://oss.sonatype.org/content/repositories/snapshots/ \
      -DpomFile=$ARTIFACT_ID_BASE-$i-$VERSION.pom \
      -Dfile=$ARTIFACT_ID_BASE-$i-$VERSION.klib \
      -Dfiles=$ARTIFACT_ID_BASE-$i-$VERSION-metadata.jar,$ARTIFACT_ID_BASE-$i-$VERSION.module,$ARTIFACT_ID_BASE-$i-$VERSION-cinterop-PhoenixCrypto.klib \
      -Dtypes=jar,module,klib \
      -Dclassifiers=metadata,,cinterop-PhoenixCrypto \
      -Dsources=$ARTIFACT_ID_BASE-$i-$VERSION-sources.jar \
      -Djavadoc=$ARTIFACT_ID_BASE-$i-$VERSION-javadoc.jar
  elif [ $i == linux ]; then
    mvn deploy:deploy-file -DrepositoryId=ossrh -Durl=https://oss.sonatype.org/content/repositories/snapshots/ \
      -DpomFile=$ARTIFACT_ID_BASE-$i-$VERSION.pom \
      -Dfile=$ARTIFACT_ID_BASE-$i-$VERSION.klib \
      -Dfiles=$ARTIFACT_ID_BASE-$i-$VERSION.module \
      -Dtypes=module \
      -Dclassifiers= \
      -Dsources=$ARTIFACT_ID_BASE-$i-$VERSION-sources.jar \
      -Djavadoc=$ARTIFACT_ID_BASE-$i-$VERSION-javadoc.jar
  else
    mvn deploy:deploy-file -DrepositoryId=ossrh -Durl=https://oss.sonatype.org/content/repositories/snapshots/ \
      -DpomFile=$ARTIFACT_ID_BASE-$i-$VERSION.pom \
      -Dfile=$ARTIFACT_ID_BASE-$i-$VERSION.jar \
      -Dfiles=$ARTIFACT_ID_BASE-$i-$VERSION.module \
      -Dtypes=module \
      -Dclassifiers= \
      -Dsources=$ARTIFACT_ID_BASE-$i-$VERSION-sources.jar \
      -Djavadoc=$ARTIFACT_ID_BASE-$i-$VERSION-javadoc.jar
  fi
  popd
  pushd .
done
