import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCompilation

buildscript {
    repositories.jcenter()
}

plugins {
    kotlin("multiplatform")
    `maven-publish`
    id("com.dorongold.task-tree") version "1.5"
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
    val cinteropLibsecp256kLocation: String by project

    val buildNativeLib = tasks.register<Exec>("build-native-lib") {
        //warning are issued at the end of command by cross-compilation to iOS, but they are only warnings ;-)
        workingDir(project.file(cinteropLibsecp256kLocation))
        commandLine("./xbuild-secp256k1.sh")
        outputs.dir("$cinteropLibsecp256kLocation/secp256k1/build/ios")
        outputs.dir("$cinteropLibsecp256kLocation/secp256k1/build/linux")
    }

    tasks.clean.get().dependsOn(tasks.register("clean-native-results"){
        destroyables.register("$cinteropLibsecp256kLocation/secp256k1/build")
        doLast{
            delete("$cinteropLibsecp256kLocation/secp256k1/build")
        }
    })

    jvm()
    linuxX64("linux")
    ios()
    targets.configureEach {
        (compilations["main"] as? KotlinNativeCompilation)?.apply {
            cinterops {
                val libsecp256k1 by creating {
                    includeDirs.headerFilterOnly(project.file("${cinteropLibsecp256kLocation}/secp256k1/include/"))
                    includeDirs(project.file("$cinteropLibsecp256kLocation/secp256k1/.libs"), "/usr/local/lib")
                    //ensure task is run after building native lib and only run if native build result has changed
                    val task = tasks[interopProcessingTaskName] as? DefaultTask
                    task?.dependsOn(buildNativeLib)
                    task?.outputs?.upToDateWhen { !buildNativeLib.get().didWork }
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
