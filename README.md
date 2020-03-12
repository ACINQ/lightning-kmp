Basic prototypes for evaluating kotlin-multiplatform in the context of eclair (Phoenix) on iOS

Modules are organized as follows:
 - secp256k1-lib: kotlin bindings for https://github.com/bitcoin-core/secp256k1. 
   - on the JVM we reuse our JNI wrapper
   - on linux64 we link statically to secp256k1.a
 - eklair-lib: Lightning-related classes. Everything is written in pure Kotlin and should work as-is on all platforms
  - basic hashes
  - noise handshake
 - eklair-node: basic node prototype, using ktor to manage connections.
