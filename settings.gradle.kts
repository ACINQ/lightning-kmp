rootProject.name="eclair-kmp"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven ("https://dl.bintray.com/kotlin/kotlin-eap")
        jcenter()
    }
}

include(
    ":",
    ":utils:bitcoin-json-rpc"
)
