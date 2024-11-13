pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
    }
}

dependencyResolutionManagement {
    repositories {
        // using the local maven repository with Kotlin Multi Platform can lead to build errors that are hard to diagnose.
        // uncomment this only if you need to experiment with snapshot dependencies that have not yet be published.
        // mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        mavenCentral()
        google()
    }
    versionCatalogs {
        create("libs") {
            library("bitcoinkmp", "fr.acinq.bitcoin", "bitcoin-kmp").version("0.20.0") // when upgrading bitcoin-kmp, keep secpJniJvmVersion in sync!
            library("secpjnijvm", "fr.acinq.secp256k1", "secp256k1-kmp-jni-jvm").version("0.15.0")

            library("kotlinx-datetime", "org.jetbrains.kotlinx", "kotlinx-datetime").version("0.6.0")

            version("kotlinx-coroutines", "1.7.3")
            library("kotlinx-coroutines-core", "org.jetbrains.kotlinx", "kotlinx-coroutines-core").versionRef("kotlinx-coroutines")

            version("kotlinx-serialization", "1.6.2")
            library("kotlinx-serialization-core", "org.jetbrains.kotlinx", "kotlinx-serialization-core").versionRef("kotlinx-serialization")
            library("kotlinx-serialization-cbor", "org.jetbrains.kotlinx", "kotlinx-serialization-cbor").versionRef("kotlinx-serialization")
            library("kotlinx-serialization-json", "org.jetbrains.kotlinx", "kotlinx-serialization-json").versionRef("kotlinx-serialization")

            version("ktor", "2.3.7")
            library("ktor-network", "io.ktor", "ktor-network").versionRef("ktor")
            library("ktor-network-tls", "io.ktor", "ktor-network-tls").versionRef("ktor")
            library("ktor-client-core", "io.ktor", "ktor-client-core").versionRef("ktor")
            library("ktor-client-auth", "io.ktor", "ktor-client-auth").versionRef("ktor")
            library("ktor-client-json", "io.ktor", "ktor-client-json").versionRef("ktor")
            library("ktor-client-contentnegotiation", "io.ktor", "ktor-client-content-negotiation").versionRef("ktor")
            library("ktor-client-okhttp", "io.ktor", "ktor-client-okhttp").versionRef("ktor")
            library("ktor-client-ios", "io.ktor", "ktor-client-ios").versionRef("ktor")
            library("ktor-client-darwin", "io.ktor", "ktor-client-darwin").versionRef("ktor")
            library("ktor-client-curl", "io.ktor", "ktor-client-curl").versionRef("ktor")
            library("ktor-serialization-kotlinx-json", "io.ktor", "ktor-serialization-kotlinx-json").versionRef("ktor")

            library("kermit", "co.touchlab", "kermit").version("2.0.2")
        }
    }
}

rootProject.name = "lightning-kmp"

include(":lightning-kmp-ios-crypto")
include(":lightning-kmp-core")

project(":lightning-kmp-ios-crypto").projectDir = file("./modules/ios-crypto")
project(":lightning-kmp-core").projectDir = file("./modules/core")