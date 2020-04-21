Basic prototype for evaluating kotlin-multiplatform in the context of eclair (Phoenix) on iOS and Android

Modules are organized as follows:
- secp256k1-lib: kotlin bindings for https://github.com/bitcoin-core/secp256k1.
  - on the JVM we reuse our JNI wrapper
  - on linux64 we link statically to secp256k1.a
- common: Lightning related module containing the node logic. Everything is written in pure Kotlin and should work as-is on all platforms.
- phoenix-android: the Phoenix app on android, using the `common` module for the node logic.


## cinterop with secp256k1

There is a git submodule in the `c/secp256k1` folder, one need to checkout it properly with `git clone --recursive` or `git submodule update`

## General

Copy the file `local.properties.example` into `local.properties` and change its values to match where your SDKs are installed.

## Android

Use Android Studio and make sure you are using the Android Studio JRE provided with the Android Studio distribution to build the project.
This app use the `jvm()` implementation of the kotlin/native `common` module

## iOS

You need `ios-autotools` and then you can build the static lib using the following (change the first arg to arm64 for a real device):

```sh
sh iconfigure x86_64 --enable-experimental --enable-module_ecdh
    make
```

- Install command line tools:

```sh
xcode-select --install
```

- Install libtool:

```sh
brew install libtool
```

> `brew` will detect the already available Apple version and thus will rename the new one into `glibtool` as expected by the other parts of the toolchain.
	
- Install GMP

```sh
ruby -e "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/master/install)" < /dev/null 2> /dev/null

brew install gmp
```

Use the following command in order to build the "**eklair**" framework:
`./gradlew :common:createFatFramework`

You can also prevent the Android counterpart to be built in case the environment is not set:
`./gradlew :common:createFatFramework -Pinclude_android=false`

The Xcode project has a build phase dedicated to build the framework.
