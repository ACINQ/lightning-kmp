import org.jetbrains.kotlin.gradle.dsl.KotlinNativeBinaryContainer
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithHostTests

buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    kotlin("multiplatform") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
    id("app.cash.sqldelight")
}

kotlin {
    jvm()

    fun KotlinNativeTargetWithHostTests.phoenixBinaries() {
        binaries {
            executable("phoenixd") {
                entryPoint = "fr.acinq.lightning.bin.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
            executable("phoenix-cli") {
                entryPoint = "fr.acinq.lightning.cli.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
        }
    }

    val currentOs = org.gradle.internal.os.OperatingSystem.current()
    if (currentOs.isLinux) {
        linuxX64 {
            phoenixBinaries()
        }
    }

    if (currentOs.isMacOsX) {
        macosX64 {
            phoenixBinaries()
        }
    }

    val ktorVersion = "2.3.8"
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktorVersion"

    sourceSets {
        commonMain {
            dependencies {
                implementation("fr.acinq.lightning:lightning-kmp:1.6.2-BIN-SNAPSHOT")
                // ktor network & tls
                implementation(ktor("network"))
                implementation(ktor("network-tls"))
                // ktor client
                implementation(ktor("client-core"))
                implementation(ktor("client-auth"))
                // ktor server
                implementation(ktor("server-core"))
                implementation(ktor("server-content-negotiation"))
                implementation(ktor("server-cio"))
                implementation(ktor("server-websockets"))
                implementation(ktor("server-auth"))
                implementation(ktor("server-status-pages")) // exception handling
                // ktor client (needed for webhook)
                implementation(ktor("client-core"))
                implementation(ktor("client-cio"))
                implementation(ktor("client-json"))
                implementation(ktor("client-content-negotiation"))
                // ktor serialization
                implementation(ktor("serialization-kotlinx-json"))
                // file system
                implementation("com.squareup.okio:okio:3.8.0")
                // CLI
                implementation("com.github.ajalt.clikt:clikt:4.2.2")
                // SQLDelight extension for consuming queries as a flow
                implementation("app.cash.sqldelight:coroutines-extensions:2.0.1")
            }
        }
        jvmMain {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.1")
            }
        }
        nativeMain {
            dependencies {
                implementation("app.cash.sqldelight:native-driver:2.0.1")
            }
        }
    }
}

// forward std input when app is run via gradle (otherwise keyboard input will return EOF)
tasks.withType<JavaExec> {
    standardInput = System.`in`
}

sqldelight {
    databases {
        create("ChannelsDatabase") {
            packageName.set("fr.acinq.phoenix.db")
            srcDirs.from("src/commonMain/sqldelight/channelsdb")
        }
        create("PaymentsDatabase") {
            packageName.set("fr.acinq.phoenix.db")
            srcDirs.from("src/commonMain/sqldelight/paymentsdb")
        }
    }
}
