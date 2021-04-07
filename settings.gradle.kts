rootProject.name = "lightning-kmp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://dl.bintray.com/kotlin/kotlin-eap")
        jcenter()
    }
}

include(
    ":PhoenixCrypto",
    ":lightning-kmp-test-fixtures"
)
