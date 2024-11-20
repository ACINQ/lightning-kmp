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
            version("kotlinx-version", "2.0.0")
            version("sqldelight", "2.0.1")
            library("kotlinx-coroutinesTest", "org.jetbrains.kotlinx", "kotlinx-coroutines-test").version("1.7.3")
            library("sqldelight-runtime", "app.cash.sqldelight", "runtime").versionRef("sqldelight")
            library("sqldelight-sqliteDriver", "app.cash.sqldelight", "sqlite-driver").versionRef("sqldelight")
            library("sqldelight-nativeDriver", "app.cash.sqldelight", "native-driver").versionRef("sqldelight")
        }
    }
}

rootProject.name = "lightning-kmp"

include(":lightning-kmp-ios-crypto")
include(":lightning-kmp-core")
include(":lightning-kmp-db-schema")
include(":lightning-kmp-db-types")

project(":lightning-kmp-ios-crypto").projectDir = file("./modules/ios-crypto")
project(":lightning-kmp-core").projectDir = file("./modules/core")
project(":lightning-kmp-db-schema").projectDir = file("./modules/db/schema")
project(":lightning-kmp-db-types").projectDir = file("./modules/db/types")