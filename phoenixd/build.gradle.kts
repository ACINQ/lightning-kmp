buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    kotlin("multiplatform") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("app.cash.sqldelight")
}

kotlin {
    jvm()

    val currentOs = org.gradle.internal.os.OperatingSystem.current()
    if (currentOs.isLinux) {
        linuxX64 {
            binaries {
                executable {
                    entryPoint = "fr.acinq.lightning.bin.main"
                    optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
                }
            }
        }
    }

    val ktorVersion = "2.3.8"
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktorVersion"

    sourceSets {
        commonMain {
            dependencies {
                implementation("fr.acinq.lightning:lightning-kmp:1.6.2-BIN-SNAPSHOT")
                implementation(ktor("network"))
                implementation(ktor("network-tls"))
                implementation(ktor("client-core"))
                implementation(ktor("client-auth"))
                implementation(ktor("server-core"))
                implementation(ktor("server-content-negotiation"))
                implementation(ktor("serialization-kotlinx-json"))
                implementation(ktor("server-cio"))
                implementation(ktor("server-websockets"))
                implementation("com.squareup.okio:okio:3.8.0")
                implementation("com.github.ajalt.clikt:clikt:4.2.2")
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
//        create("ChannelsDatabase") {
//            packageName.set("fr.acinq.phoenix.db")
//            //sourceFolders = listOf("sqldelight/channelsdb")
//        }
//    database("PaymentsDatabase") {
//        packageName = "fr.acinq.phoenix.db"
//        sourceFolders = listOf("sqldelight/paymentsdb")
//    }
    }
}
