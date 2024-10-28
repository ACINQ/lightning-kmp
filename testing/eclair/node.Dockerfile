FROM adoptopenjdk/openjdk11:jdk-11.0.3_7-alpine as BUILD

RUN apk add --no-cache bash git

WORKDIR /code
RUN git clone -b mvn-wrapper https://github.com/SalomonBrys/eclair.git

WORKDIR /code/eclair
RUN ./mvnw install -pl eclair-node -am -DskipTests


FROM openjdk:11.0.4-jre-slim

RUN apt-get update && apt-get install -y bash jq curl unzip

WORKDIR /root
COPY nodeA nodeA
COPY nodeB nodeB

COPY --from=BUILD /code/eclair/eclair-node/target/eclair-node-*.zip .

RUN unzip eclair-node-*.zip
RUN rm eclair-node-*.zip
RUN mv eclair-node-* eclair-node

WORKDIR /root/eclair-node/bin

CMD ./eclair-node.sh -Declair.printToConsole -Declair.datadir=$NODE_NAME > "/tmp/$NODE_NAME-${date +%d-%m-%Y_%H-%M-%S}.log"
