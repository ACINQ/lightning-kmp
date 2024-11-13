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
}

rootProject.name = "lightning-kmp"

include(":ios-crypto")
project(":ios-crypto").run {
    projectDir = file("./modules/ios-crypto")
    name = "lightning-kmp-ios-crypto"
}

include(":core")
project(":core").run {
    projectDir = file("./modules/core")
    name = "lightning-kmp-core"
}