[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-blue.svg?style=flat&logo=kotlin)](http://kotlinlang.org)
[![Maven Central](https://img.shields.io/maven-central/v/fr.acinq.lightning/lightning-kmp)](https://search.maven.org/search?q=g:fr.acinq.lightning%20a:lightning-kmp*)
![Github Actions](https://github.com/ACINQ/lightning-kmp/actions/workflows/test.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**lightning-kmp** is a [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) implementation of the Lightning Network (see the [Lightning Network Specifications (BOLTs)](https://github.com/lightning/bolts)) optimized for mobile wallets. It can run on many platforms, including mobile devices (iOS and Android).

It is different from [eclair](https://github.com/ACINQ/eclair) which is an implementation optimized for servers (routing nodes).
It shares a lot of architecture choices with eclair though, which comes from years of experience developing one of the main lightning implementations.
But it optimizes completely different scenarios, as wallets will not relay payments but rather send and receive them.
Read [this article](https://medium.com/@ACINQ/when-ios-cdf798d5f8ef) for more details.

**lightning-kmp** is used in [Phoenix](https://phoenix.acinq.co/), the best non-custodial Lightning Wallet!

## Installation

See instructions [here](https://github.com/ACINQ/lightning-kmp/blob/master/BUILD.md) to build and test the library.

## Recovering on-chain funds

See instructions [here](./RECOVERY.md) to recover on-chain funds.

## Contributing

We use GitHub for bug tracking. Search the existing issues for your bug and create a new one if needed.

Contribute to the project by submitting pull requests.
Review is done by members of the ACINQ team.
Please read the guidelines [here](https://github.com/ACINQ/lightning-kmp/blob/master/CONTRIBUTING.md).

## Resources

* [1] [The Bitcoin Lightning Network: Scalable Off-Chain Instant Payments](https://lightning.network/lightning-network-paper.pdf) by Joseph Poon and Thaddeus Dryja
* [2] [Reaching The Ground With Lightning](https://github.com/ElementsProject/lightning/blob/master/doc/miscellaneous/deployable-lightning.pdf) by Rusty Russell
* [3] [When Phoenix on iOS?](https://medium.com/@ACINQ/when-ios-cdf798d5f8ef) - A blog post detailing why we created this library
