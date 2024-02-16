plugins {
    kotlin("multiplatform") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
}

kotlin {
    jvm()

    val currentOs = org.gradle.internal.os.OperatingSystem.current()
    if (currentOs.isLinux) {
        linuxX64 {
            binaries {
                executable {
                    entryPoint = "fr.acinq.lightning.bin.main"
                }
            }
        }
    }

    val ktorVersion = "2.3.7"
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktorVersion"

    sourceSets {
        commonMain {
            dependencies {
                implementation("fr.acinq.lightning:lightning-kmp:1.6-BIN-SNAPSHOT")
                implementation(ktor("network"))
                implementation(ktor("network-tls"))
                implementation(ktor("client-core"))
                implementation(ktor("client-auth"))
                implementation(ktor("server-core"))
                implementation(ktor("server-content-negotiation"))
                implementation(ktor("serialization-kotlinx-json"))
            }
        }

        nativeMain {
            dependencies {
                implementation(ktor("server-cio"))
            }
        }
    }
}