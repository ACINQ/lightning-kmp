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

if (include_android == "true") {
    println("Include Android")
    include(":phoenix-android")
}
