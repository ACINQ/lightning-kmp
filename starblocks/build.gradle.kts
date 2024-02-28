buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    kotlin("multiplatform") version "1.9.23"
    kotlin("plugin.serialization") version "1.9.23"
}

kotlin {
    jvm()

    val currentOs = org.gradle.internal.os.OperatingSystem.current()
    if (currentOs.isLinux) {
        linuxX64 {
            binaries {
                executable {
                    entryPoint = "fr.acinq.starblocks.main"
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
                implementation(ktor("network"))
                implementation(ktor("client-core"))
                implementation(ktor("client-auth"))
                implementation(ktor("client-cio"))
                implementation(ktor("server-core"))
                implementation(ktor("server-content-negotiation"))
                implementation(ktor("serialization-kotlinx-json"))
                implementation(ktor("server-cio"))
                implementation(ktor("server-websockets"))
                implementation(ktor("server-status-pages")) // exception handling
                implementation("com.github.ajalt.clikt:clikt:4.2.2")
            }
        }
    }
}

// forward std input when app is run via gradle (otherwise keyboard input will return EOF)
tasks.withType<JavaExec> {
    standardInput = System.`in`
}

