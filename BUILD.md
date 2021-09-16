# Building

lightning-kmp is a [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) library.
It can run on many different platforms, including mobile devices (iOS and Android).

## Requirements

On all platforms:

- [Docker](https://www.docker.com/) 18.03 or newer is required if you want to run all tests

For iOS, you need to have [Xcode](https://developer.apple.com/xcode/) and [Homebrew](https://brew.sh/) installed, and then:

```sh
brew install libtool
brew install gmp
```

## Required dependency

This project currently depends on a fork of the `kodein-logs` library. This lib must be built manually and deployed to the local maven repository before being able to build `lightning-kmp`. To do so:
1. clone [the GitHub repo](https://github.com/dpad85/Kodein-Log)
```
 git clone git@github.com:dpad85/Kodein-Log.git
 cd Kodein-Log
```
2. checkout the `0.10.1-utc` tag
```
git checkout 0.10.1-utc
```
4. build and deploy the library:
```
./gradlew build
./gradlew publishToMavenLocal
```

Note: this dependency is a temporary workaround and will be removed as soon as possible.

## Build

You should start by cloning the repository locally:

```sh
git clone git@github.com:ACINQ/lightning-kmp.git
cd lightning-kmp
```

To build the project library and install it locally, you can run:

```sh
./gradlew :build
./gradlew :publishToMavenLocal
```

To run all tests on all platforms:

```sh
./gradlew allTests
```

To run tests on a single platform, for example the JVM:

```sh
./gradlew jvmTest
```
