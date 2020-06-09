import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.FatFrameworkTask

plugins {
    application
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

group = "fr.acinq.eklair"
version = "1.0-SNAPSHOT"

application {
    mainClassName = "fr.acinq.eklair.Boot"
}

repositories {
    mavenLocal()
    google()
    jcenter()
    mavenCentral()
}

kotlin {
    /* Targets configuration omitted.
    *  To find out how to configure the targets, please follow the link:
    *  https://kotlinlang.org/docs/reference/building-mpp-with-gradle.html#setting-up-targets */
    jvm() {

    }
    val isWinHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    if (!isWinHost) {
        linuxX64("linux")
    }

    ios {
        binaries {
            framework()
        }
    }

    sourceSets {
        val ktor_version: String by extra
        val coroutines_version: String by extra
        val coroutines_mt_version = "$coroutines_version-native-mt"
        val serialization_version: String by extra

        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("fr.acinq:bitcoink:1.0-SNAPSHOT")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_mt_version")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-common:$coroutines_mt_version")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-common:$serialization_version")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("io.ktor:ktor-client-core:$ktor_version")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(kotlin("stdlib-jdk8"))
                implementation("fr.acinq.bitcoin:secp256k1-jni:1.3")
                implementation("fr.acinq:bitcoink-jvm:1.0-SNAPSHOT")
                implementation("io.ktor:ktor-client-okhttp:$ktor_version")
                implementation("io.ktor:ktor-network:$ktor_version")
                implementation("org.slf4j:slf4j-api:1.7.29")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:$serialization_version")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.bouncycastle:bcprov-jdk15on:1.64")
            }
        }
        if (!isWinHost) {
            val linuxMain by getting {
                dependencies {
                    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-native:$coroutines_mt_version")
                    implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime-native:$serialization_version")
                }
            }
            val linuxTest by getting {
                dependencies {
                    implementation("io.ktor:ktor-client-curl:$ktor_version")
                }
            }
        }
        val iosMain by getting {
            dependencies {
                implementation(kotlin("stdlib"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_mt_version")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-native:$coroutines_mt_version")
            }
        }
        val iosTest by getting {
            dependencies {
                implementation("io.ktor:ktor-client-ios:$ktor_version")
            }
        }
    }

    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.ExperimentalStdlibApi")
    }

    // Create a task building a fat framework.
    tasks.create("createFatFramework", FatFrameworkTask::class) {
        val buildType: String = project.findProperty("kotlin.build.type")?.toString() ?: "DEBUG"

        // The fat framework must have the same base name as the initial frameworks.
        baseName = "eklair"

        // The default destination directory is '<build directory>/fat-framework'.
        destinationDir = buildDir.resolve("eklair/${buildType.toLowerCase()}")

        val iosTargets = listOf(targets.findByName("iosArm64") as? KotlinNativeTarget, targets.findByName("iosX64") as? KotlinNativeTarget)
        // Specify the frameworks to be merged.
        val frameworksBinaries = iosTargets.mapNotNull { it?.binaries?.getFramework(buildType) }
        from(frameworksBinaries)
        dependsOn(frameworksBinaries.map { it.linkTask })

        // disable gradle's up to date checking
        outputs.upToDateWhen { false }

        doLast {
            val srcFile: File = destinationDir
            val targetDir = System.getProperty("configuration.build.dir") ?: project.buildDir.path
            println("\uD83C\uDF4E Copying ${srcFile} to ${targetDir}")
            copy {
                from(srcFile)
                into(targetDir)
                include("*.framework/**")
                include("*.framework.dSYM/**")
            }
        }
    }
}
