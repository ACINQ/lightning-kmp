import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    application
    kotlin("multiplatform") version "1.4-M3"
    kotlin("plugin.serialization") version "1.4-M3"
    `maven-publish`
}

group = "fr.acinq.eklair"
version = "0.2.0-1.4-M3"

application {
    mainClassName = "fr.acinq.eklair.Boot"
}

repositories {
    mavenLocal()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven("https://dl.bintray.com/kotlin/ktor")
    maven("https://dl.bintray.com/kotlin/kotlin-eap")
    maven("https://dl.bintray.com/kodein-framework/kodein-dev")
    maven("https://dl.bintray.com/acinq/libs")
    google()
    jcenter()
}

val currentOs = org.gradle.internal.os.OperatingSystem.current()

val ktorVersion = "1.3.2-1.4-M3"
val secp256k1Version = "0.3.0-1.4-M3"

kotlin {

    val commonMain by sourceSets.getting {
        dependencies {
            implementation(kotlin("stdlib-common"))

            api("fr.acinq.bitcoink:bitcoink:0.2.0-1.4-M3")
            api("fr.acinq.secp256k1:secp256k1:$secp256k1Version")
            api("org.kodein.log:kodein-log:0.3.0-kotlin-1.4-M3-36-b27d97f")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7-1.4-M3")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0-1.4-M3")
        }
    }
    val commonTest by sourceSets.getting {
        fun ktorClient(module: String, version: String = ktorVersion) = "io.ktor:ktor-client-$module:$version"

        dependencies {
            implementation(kotlin("test-common"))
            implementation(kotlin("test-annotations-common"))
            implementation("io.ktor:ktor-client-core:$ktorVersion")
        }
    }

    jvm {
        compilations["main"].kotlinOptions.jvmTarget = "1.8"
        compilations["main"].defaultSourceSet.dependencies {
            implementation(kotlin("stdlib-jdk8"))
            implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
            implementation("io.ktor:ktor-network:$ktorVersion")
            implementation("io.ktor:ktor-network-tls:$ktorVersion")
            implementation("org.slf4j:slf4j-api:1.7.29")
        }
        compilations["test"].defaultSourceSet.dependencies {
            val target = when {
                currentOs.isLinux -> "linux"
                currentOs.isMacOsX -> "darwin"
                currentOs.isWindows -> "mingw"
                else -> error("UnsupportedmOS $currentOs")
            }
            implementation("fr.acinq.secp256k1:secp256k1-jni-jvm-$target:$secp256k1Version")
            implementation(kotlin("test-junit"))
            implementation("org.bouncycastle:bcprov-jdk15on:1.64")

        }
    }

    val nativeMain by sourceSets.creating { dependsOn(commonMain) }
    val nativeTest by sourceSets.creating { dependsOn(commonTest) }

    if (currentOs.isLinux) {
        linuxX64("linux") {
            compilations["main"].defaultSourceSet {
                dependsOn(nativeMain)
            }
            compilations["test"].defaultSourceSet {
                dependsOn(nativeTest)
                dependencies {
                    implementation("io.ktor:ktor-client-curl:$ktorVersion")
                }
            }
        }
    }

    if (currentOs.isMacOsX) {
        ios {
            compilations["main"].defaultSourceSet {
                dependsOn(nativeMain)
            }
            compilations["test"].defaultSourceSet {
                dependsOn(nativeTest)
                dependencies {
                    implementation("io.ktor:ktor-client-ios:$ktorVersion")
                }
            }
        }
    }

    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.RequiresOptIn")
        languageSettings.useExperimentalAnnotation("kotlin.ExperimentalStdlibApi")
    }
}


// Disable cross compilation
afterEvaluate {
    val targets = when {
        currentOs.isLinux -> listOf()
        else -> listOf("linuxX64")
    }.mapNotNull { kotlin.targets.findByName(it) as? KotlinNativeTarget }

    configure(targets) {
        compilations.all {
            cinterops.all { tasks[interopProcessingTaskName].enabled = false }
            compileKotlinTask.enabled = false
            tasks[processResourcesTaskName].enabled = false
        }
        binaries.all { linkTask.enabled = false }

        mavenPublication {
            val publicationToDisable = this
            tasks.withType<AbstractPublishToMaven>().all { onlyIf { publication != publicationToDisable } }
            tasks.withType<GenerateModuleMetadata>().all { onlyIf { publication.get() != publicationToDisable } }
        }
    }
}

afterEvaluate {
    tasks.withType<AbstractTestTask>() {
        testLogging {
            events("passed", "skipped", "failed", "standard_out", "standard_error")
            showExceptions = true
            showStackTraces = true
        }
    }
}
