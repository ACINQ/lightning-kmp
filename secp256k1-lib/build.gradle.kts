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

    jvm()
    linuxX64("linux") {
        compilations["main"].apply {
            cinterops {
                val libsecp256k1 by creating {
                    includeDirs.headerFilterOnly("/home/fabrice/code/secp256k1/include")
                }
            }
            //kotlinOptions.freeCompilerArgs = listOf("-include-binary", "/home/fabrice/code/secp256k1/.libs/libsecp256k1.a")
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
    }

    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.ExperimentalStdlibApi")
    }
}