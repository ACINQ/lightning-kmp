Eclair KMP is an implementation of Lightning written in Kotlin. Thanks to [Kotlin Multiplatform](https://kotlinlang.org/docs/reference/multiplatform.html) it can run on different platforms, including mobile devices (iOS and Android).

## Dependencies

For iOS, you need to have Xcode & [Homebrew](https://brew.sh/) installed, and then:

```
brew install libtool
brew install gmp
```

## Build & Tests

#### Run tests

```
git clone https://github.com/ACINQ/eclair-kmp.git
cd eclair-kmp
./gradlew allTests
```

#### Use as a library

```
./gradlew :build
./gradlew :publishToMavenLocal
```

## Contributing

We use GitHub for bug tracking. Search the existing issues for your bug and create a new one if needed.

Contribute to the project by submitting pull requests. Review is done by members of the ACINQ team. Read the guidelines [here](https://github.com/ACINQ/eclair-kmp/blob/master/CONTRIBUTING.md).
