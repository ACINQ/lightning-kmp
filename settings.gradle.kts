rootProject.name = "eclair-kmp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
        jcenter()
    }
}

include(
    ":PhoenixCrypto",
    ":eclair-kmp-test-fixtures"
)
