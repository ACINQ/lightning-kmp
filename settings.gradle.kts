rootProject.name="eklair"

val kotlin_version: String by extra
val include_android: String by extra

pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.namespace == "org.jetbrains.kotlin:kotlin-serialization") {
                useModule("org.jetbrains.kotlin:kotlin-serialization:$kotlin_version")
            }
        }
    }
}
enableFeaturePreview("GRADLE_METADATA")

include(":common")
include(":secp256k1-lib")

println("Checking Android part")
if (include_android == "true") {
    println("Android included")
    include(":phoenix-android")
}
