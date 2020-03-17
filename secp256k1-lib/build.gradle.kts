import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation

plugins {
    kotlin("multiplatform") version "1.3.70"
    `maven-publish`
}

group = "fr.acinq.eklair"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    /* Targets configuration omitted.
    *  To find out how to configure the targets, please follow the link:
    *  https://kotlinlang.org/docs/reference/building-mpp-with-gradle.html#setting-up-targets */
    val cinterop_libsecp256k_location: String by project

    jvm()
    linuxX64("linux")
    iosX64("ios") {
        binaries {
            framework()
        }
    }
    targets.configureEach {
        (compilations["main"] as? KotlinNativeCompilation)?.apply {
            cinterops {
                val libsecp256k1 by creating {
                    includeDirs.headerFilterOnly(project.file("${cinterop_libsecp256k_location}/secp256k1/include/"))
                    includeDirs(project.file("$cinterop_libsecp256k_location/secp256k1/.libs"), "/usr/local/lib")
                }
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                implementation("fr.acinq.bitcoin:secp256k1-jni:1.3")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.bouncycastle:bcprov-jdk15on:1.64")
            }
        }
        val iosMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
            }
        }

        val iosTest by getting {
            dependencies {
            }
        }
    }

    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.ExperimentalStdlibApi")
    }
}
