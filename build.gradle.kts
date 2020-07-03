import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.FatFrameworkTask

plugins {
    application
    kotlin("multiplatform") version "1.4-M2-mt"
    kotlin("plugin.serialization") version "1.4-M2-mt"
    `maven-publish`
}

group = "fr.acinq.eklair"
version = "0.1.0-1.4-M2"

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

val ktor_version = "1.3.2-1.4-M2"

kotlin {

    val commonMain by sourceSets.getting {
        dependencies {
            implementation(kotlin("stdlib-common"))
            implementation("fr.acinq:bitcoink:0.1.0-1.4-M2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.7-native-mt-1.4-M2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-runtime:0.20.0-1.4-M2")
            implementation("org.kodein.log:kodein-log:0.2.0-1.4-M2-dev-20")
        }
    }
    val commonTest by sourceSets.getting {
        dependencies {
            implementation(kotlin("test-common"))
            implementation(kotlin("test-annotations-common"))
            implementation("io.ktor:ktor-client-core:$ktor_version")
        }
    }

    jvm {
        compilations["main"].kotlinOptions.jvmTarget = "1.8"
        compilations["main"].defaultSourceSet.dependencies {
            implementation(kotlin("stdlib-jdk8"))
            implementation("io.ktor:ktor-client-okhttp:$ktor_version")
            implementation("io.ktor:ktor-network:$ktor_version")
            implementation("org.slf4j:slf4j-api:1.7.29")
        }
        compilations["test"].defaultSourceSet.dependencies {
            implementation("fr.acinq.secp256k1:secp256k1-jni-jvm:0.1.0-1.4-M2")
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
                    implementation("io.ktor:ktor-client-curl:$ktor_version")
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
                    implementation("io.ktor:ktor-client-ios:$ktor_version")
                }
            }
        }
    }

    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.RequiresOptIn")
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
