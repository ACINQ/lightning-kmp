rootProject.name="eklair"

val kotlin_version: String by extra
val include_android: String by extra

include(":common")
include(":secp256k1-lib")

if (include_android == "true") {
    println("Include Android")
    include(":phoenix-android")
}

//pluginManagement {
//    resolutionStrategy {
//        eachPlugin {
//            if (requested.id.namespace == "org.jetbrains.kotlin:kotlin-serialization") {
//                useModule("org.jetbrains.kotlin:kotlin-serialization:$kotlin_version")
//            }
//        }
//    }
//}

enableFeaturePreview("GRADLE_METADATA")
