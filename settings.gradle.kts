pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // using the local maven repository with Kotlin Multi Platform can lead to build errors that are hard to diagnose.
        // uncomment this only if you need to experiment with snapshot dependencies that have not yet be published.
        // mavenLocal()
        mavenCentral()
        google()
        maven("https://central.sonatype.com/repository/maven-snapshots")
    }
}

rootProject.name = "lightning-kmp"

include(":lightning-kmp-ios-crypto")
include(":lightning-kmp-core")

project(":lightning-kmp-ios-crypto").projectDir = file("./modules/ios-crypto")
project(":lightning-kmp-core").projectDir = file("./modules/core")