Basic prototypes for evaluating kotlin-multiplatform in the context of eclair (Phoenix) on iOS

Modules are organized as follows:
 - secp256k1-lib: kotlin bindings for https://github.com/bitcoin-core/secp256k1. 
   - on the JVM we reuse our JNI wrapper
   - on linux64 we link statically to secp256k1.a
 - eklair-lib: Lightning-related classes. Everything is written in pure Kotlin and should work as-is on all platforms
  - basic hashes
  - noise handshake
 - eklair-node: basic node prototype, using ktor to manage connections.


## cinterop with secp256k1

There is a git submodule in the `c/secp256k1` folder, one need to checkout it properly with `git clone --recursive` or `git submodule update`

### iOS

You need `ios-autotools` and then you can build the static lib using the following (change the first arg to arm64 for a real device):

```sh
iconfigure x86_64 --enable-experimental --enable-module_ecdh
make
```
