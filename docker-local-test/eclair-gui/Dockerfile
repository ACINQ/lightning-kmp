FROM ubuntu


RUN apt-get update

RUN DEBIAN_FRONTEND="noninteractive" apt-get -y install git openjdk-11-jdk unzip xorg


WORKDIR /code

RUN git clone -b mvn-wrapper https://github.com/SalomonBrys/eclair.git

WORKDIR /code/eclair

RUN ./mvnw install -DskipTests


WORKDIR /root

COPY dot-eclair .eclair


WORKDIR /app

RUN unzip /code/eclair/eclair-node-gui/target/eclair-node-gui-*.zip

RUN mv eclair-node-gui-* eclair-node-gui

COPY entry-point.sh entry-point.sh



ENTRYPOINT [ "./entry-point.sh" ]
