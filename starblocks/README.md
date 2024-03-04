Starblocks is a demo web store using Phoenixd to sell fake products on testnet.

## Build for production

```sh
// macos
./gradlew clean packageMacosX64

//linux
./gradlew clean packageLinuxX64
```

## Run project

```shell
./starblocks.kexe --phoenixd=localhost:8080 --webdir=web
```

Starblocks needs phoenixd to run.

## Development

First build the website:
```shell
cd src/commonMain/resources/web
```

Then, to run the server on the JVM:
```sh
./gradlew jvmRun --args="--webdir src/commonMain/resources/web/dist" -DmainClass=fr.acinq.starblocks.MainKt --quiet
```
