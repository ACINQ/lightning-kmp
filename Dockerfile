FROM adoptopenjdk/openjdk11:jdk-11.0.3_7-alpine as BUILD

# Setup maven, we don't use https://hub.docker.com/_/maven/ as it declare .m2 as volume, we loose all mvn cache
# We can alternatively do as proposed by https://github.com/carlossg/docker-maven#packaging-a-local-repository-with-the-image
# this was meant to make the image smaller, but we use multi-stage build so we don't care
RUN apk add --no-cache curl tar bash

# Let's fetch eclair dependencies, so that Docker can cache them
# This way we won't have to fetch dependencies again if only the source code changes
# The easiest way to reliably get dependencies is to build the project with no sources
WORKDIR /usr/src
COPY . .
RUN ./gradlew lightning-kmp-node:assemble

# We currently use a debian image for runtime because of some jni-related issue with sqlite
FROM openjdk:11.0.4-jre-slim
WORKDIR /app

# install jq for eclair-cli
RUN apt-get update && apt-get install -y bash jq curl unzip

# we only need the eclair-node.zip to run
COPY --from=BUILD /usr/src/lightning-kmp-node/build/distributions/lightning-kmp-node-*.zip ./lightning-kmp-node.zip
RUN unzip lightning-kmp-node.zip && mv lightning-kmp-node-* lightning-kmp-node && chmod +x lightning-kmp-node/bin/lightning-kmp-node

ENV PHOENIX_DATADIR=/data
ENV JAVA_OPTS=

RUN mkdir -p "$PHOENIX_DATADIR"
VOLUME [ "/data" ]

ENTRYPOINT JAVA_OPTS="${JAVA_OPTS} -Dphoenix.datadir=${PHOENIX_DATADIR}" lightning-kmp-node/bin/lightning-kmp-node
