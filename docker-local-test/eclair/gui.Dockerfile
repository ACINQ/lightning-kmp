FROM ubuntu

ARG user
ARG uid
ARG gid

RUN apt-get update
RUN DEBIAN_FRONTEND="noninteractive" apt-get -y install curl git jq openjdk-11-jdk unzip xorg

WORKDIR /code
RUN git clone -b mvn-wrapper https://github.com/SalomonBrys/eclair.git

WORKDIR /code/eclair
RUN ./mvnw install -DskipTests

ENV USERNAME ${user}
RUN useradd -m $USERNAME && \
        echo "$USERNAME:$USERNAME" | chpasswd && \
        usermod --shell /bin/bash $USERNAME && \
        usermod  --uid ${uid} $USERNAME && \
        groupmod --gid ${gid} $USERNAME
USER ${user}

WORKDIR /home/${user}
COPY --chown=${uid}:${gid} dot-eclair .eclair

RUN unzip /code/eclair/eclair-node-gui/target/eclair-node-gui-*.zip
RUN mv eclair-node-gui-* eclair-node-gui

CMD ./eclair-node-gui/bin/eclair-node-gui.sh -Declair.printToConsole
