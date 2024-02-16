rootProject.name = "lightning-kmp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
    }
}

include(":PhoenixCrypto")
include(":phoenixd")
