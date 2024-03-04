buildscript {
    repositories {
        google()
        mavenCentral()
    }
}

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
                    entryPoint = "fr.acinq.starblocks.main"
                    optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
                }
            }
        }
    }

    macosX64 {
        binaries {
            executable {
                entryPoint = "fr.acinq.starblocks.main"
                optimized = false // without this, release mode throws 'Index 0 out of bounds for length 0' in StaticInitializersOptimization.kt
            }
        }
    }

    val ktorVersion = "2.3.8"
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktorVersion"

    sourceSets {
        commonMain {
            dependencies {
                // ktor
                implementation(ktor("network"))
                // ktor client (to talk with phoenixd)
                implementation(ktor("client-core"))
                implementation(ktor("client-auth"))
                implementation(ktor("client-cio"))
                implementation(ktor("client-json"))
                implementation(ktor("client-content-negotiation"))
                implementation(ktor("client-websockets"))
                // ktor server
                implementation(ktor("server-core"))
                implementation(ktor("server-content-negotiation"))
                implementation(ktor("serialization-kotlinx-json"))
                implementation(ktor("server-cio"))
                implementation(ktor("server-websockets"))
                implementation(ktor("server-status-pages")) // exception handling
                implementation(ktor("server-cors")) // cors plugin
                // command line interface
                implementation("com.github.ajalt.clikt:clikt:4.2.2")
                // file system
                implementation("com.squareup.okio:okio:3.8.0")
            }
        }
    }
}

// forward std input when app is run via gradle (otherwise keyboard input will return EOF)
tasks.withType<JavaExec> {
    standardInput = System.`in`
}

// custom tasks to build and package starblocks
val npmBuild by tasks.creating(Exec::class) {
    group = "build"
    description = "build the starblocks website for production with NPM, expects an HTTPS api"
    workingDir = projectDir.resolve("src/commonMain/resources/web")
    commandLine("npm", "install")
    commandLine("npm", "run", "build")
    dependsOn()
}

val npmCopy by tasks.register("npmCopy", Copy::class) {
    group = "package"
    description = "copy the starblocks website to build/package"
    from("$projectDir/src/commonMain/resources/web/dist") {
        include("**/*")
    }
    into("$projectDir/build/package/web")
    dependsOn(npmBuild)
}

val npmPackageMacosX64 by tasks.register("packageMacosX64", Copy::class) {
    group = "package"
    description = "build the website for production and the macosX64 release executable, and copy the output to build/package"

    from("$projectDir/build/bin/macosX64/releaseExecutable") {
        include("**/*")
    }
    into("$projectDir/build/package/")
    dependsOn(npmCopy)
    dependsOn(":starblocks:linkReleaseExecutableMacosX64")
}

val npmPackageLinuxX64 by tasks.register("packageLinuxX64", Copy::class) {
    group = "package"
    description = "build the website for production and the linuxX64 release executable, and copy the output to build/package"

    from("$projectDir/build/bin/linuxX64/releaseExecutable") {
        include("**/*")
    }
    into("$projectDir/build/package/")
    dependsOn(npmCopy)
    dependsOn(":starblocks:linkReleaseExecutableMacosX64")
}
