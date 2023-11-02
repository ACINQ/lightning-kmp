import org.jetbrains.dokka.Platform
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    kotlin("multiplatform") version "1.8.21"
    kotlin("plugin.serialization") version "1.8.21"
    id("org.jetbrains.dokka") version "1.8.10"
    `maven-publish`
}

allprojects {
    group = "fr.acinq.lightning"
    version = "1.5.12-SNAPSHOT"

    repositories {
        // using the local maven repository with Kotlin Multi Platform can lead to build errors that are hard to diagnose.
        // uncomment this only if you need to experiment with snapshot dependencies that have not yet be published.
        // mavenLocal()
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        mavenCentral()
        google()
    }
}

val currentOs = org.gradle.internal.os.OperatingSystem.current()

kotlin {
    val ktorVersion: String by extra { "2.3.2" }
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktorVersion"
    val serializationVersion = "1.5.1"
    val coroutineVersion = "1.7.2"

    val commonMain by sourceSets.getting {
        dependencies {
            api("fr.acinq.bitcoin:bitcoin-kmp:0.13.0") // when upgrading, keep secp256k1-kmp-jni-jvm in sync below
            api("org.kodein.log:canard:0.18.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutineVersion")
            api("org.jetbrains.kotlinx:kotlinx-serialization-core:$serializationVersion")
            api("org.jetbrains.kotlinx:kotlinx-serialization-cbor:$serializationVersion")
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
        }
    }
    val commonTest by sourceSets.getting {
        dependencies {
            api(ktor("client-core"))
            api(ktor("client-auth"))
            api(ktor("client-json"))
            api(ktor("client-content-negotiation"))
            api(ktor("serialization-kotlinx-json"))
            implementation(kotlin("test-common"))
            implementation(kotlin("test-annotations-common"))
            implementation("org.kodein.memory:kodein-memory-files:0.8.1")
        }
    }

    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        compilations["main"].defaultSourceSet.dependencies {
            api(ktor("client-okhttp"))
            api(ktor("network"))
            api(ktor("network-tls"))
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.10.1")
            implementation("org.slf4j:slf4j-api:1.7.36")
            api("org.xerial:sqlite-jdbc:3.32.3.2")
        }
        compilations["test"].defaultSourceSet.dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.bouncycastle:bcprov-jdk15on:1.64")
            implementation("ch.qos.logback:logback-classic:1.2.3")
        }
    }

    if (currentOs.isLinux || currentOs.isMacOsX) {

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
                        implementation(ktor("client-curl"))
                    }
                }
            }
        }

        if (currentOs.isMacOsX) {
            ios {
                val platform = when (name) {
                    "iosX64" -> "iphonesimulator"
                    "iosArm64" -> "iphoneos"
                    else -> error("Unsupported target $name")
                }

                compilations["main"].cinterops.create("PhoenixCrypto") {
                    val interopTask = tasks[interopProcessingTaskName]
                    interopTask.dependsOn(":PhoenixCrypto:buildCrypto${platform.capitalize()}")
                    includeDirs.headerFilterOnly("$rootDir/PhoenixCrypto/build/Release-$platform/include")
                }
                compilations["main"].defaultSourceSet {
                    dependsOn(nativeMain)
                }
                compilations["test"].defaultSourceSet {
                    dependsOn(nativeTest)
                    dependencies {
                        implementation(ktor("client-ios"))
                    }
                }
            }
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.RequiresOptIn")
        languageSettings.optIn("kotlin.ExperimentalStdlibApi")
    }

    // Configure all compilations of all targets:
    targets.all {
        compilations.all {
            kotlinOptions {
                allWarningsAsErrors = true
            }
        }
    }
}

val dokkaOutputDir = buildDir.resolve("dokka")

tasks.dokkaHtml {
    outputDirectory.set(file(dokkaOutputDir))
    dokkaSourceSets {
        configureEach {
            val platformName = when (platform.get()) {
                Platform.jvm -> "jvm"
                Platform.js -> "js"
                Platform.native -> "native"
                Platform.common -> "common"
                Platform.wasm -> "wasm"
            }
            displayName.set(platformName)

            perPackageOption {
                matchingRegex.set(".*\\.internal.*") // will match all .internal packages and sub-packages
                suppress.set(true)
            }
        }
    }
}

val deleteDokkaOutputDir by tasks.register<Delete>("deleteDokkaOutputDirectory") {
    delete(dokkaOutputDir)
}


val javadocJar = tasks.create<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(deleteDokkaOutputDir, tasks.dokkaHtml)
    from(dokkaOutputDir)
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        version = project.version.toString()
        artifact(javadocJar)
        pom {
            name.set("Kotlin Multiplatform Lightning Network Engine")
            description.set("A Kotlin Multiplatform implementation of the Lightning Network")
            url.set("https://github.com/ACINQ/lightning-kmp")
            licenses {
                license {
                    name.set("Apache License v2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0")
                }
            }
            issueManagement {
                system.set("Github")
                url.set("https://github.com/ACINQ/lightning-kmp/issues")
            }
            scm {
                connection.set("https://github.com/ACINQ/lightning-kmp.git")
                url.set("https://github.com/ACINQ/lightning-kmp")
            }
            developers {
                developer {
                    name.set("ACINQ")
                    email.set("hello@acinq.co")
                }
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

afterEvaluate {
    tasks.withType<AbstractTestTask> {
        val verboseTests = project.findProperty("verboseTests") == "ON"
        testLogging {
            showExceptions = true
            showStackTraces = true
            if (verboseTests) {
                events("passed", "skipped", "failed")
                showStandardStreams = true
            } else {
                events("skipped", "failed")
            }
            afterSuite(KotlinClosure2({ desc: TestDescriptor, result: TestResult ->
                if (desc.parent == null && result.failedTestCount == 0L) {
                    println("${result.testCount} tests completed: ${result.successfulTestCount} passed, ${result.failedTestCount} failed, ${result.skippedTestCount} skipped")
                }
            }))
        }
    }
    tasks.withType<org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest> {
        environment("TEST_RESOURCES_PATH", projectDir.resolve("src/commonTest/resources"))
    }

    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest> {
        environment("TEST_RESOURCES_PATH", projectDir.resolve("src/commonTest/resources"))
    }

    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest> {
        environment("SIMCTL_CHILD_TEST_RESOURCES_PATH", projectDir.resolve("src/commonTest/resources"))
    }
}

/*
Electrum integration test environment + tasks configuration
 */
val dockerTestEnv by tasks.creating(Exec::class) {
    workingDir = projectDir.resolve("docker-local-test")
    commandLine("bash", "env.sh", "remove", "net-create", "btc-create", "elx-create", "btc-start", "elx-start")
    doLast {
        gradle.buildFinished {
            exec {
                println("Cleaning up dockers...")
                workingDir = projectDir.resolve("docker-local-test")
                commandLine("bash", "env.sh", "elx-stop", "btc-stop", "remove")
            }
        }
    }
}

val includeIntegrationTests = project.findProperty("integrationTests") == "include"
tasks.withType<AbstractTestTask> {
    if (includeIntegrationTests) {
        dependsOn(dockerTestEnv)
    } else {
        filter.excludeTestsMatching("*IntegrationTest")
    }
}

// Linux native does not support integration tests (sockets are not implemented in Linux native)
if (currentOs.isLinux) {
    val linuxTest by tasks.getting(KotlinNativeTest::class) {
        filter.excludeTestsMatching("*IntegrationTest")
        filter.excludeTestsMatching("*ElectrumClientTest")
        filter.excludeTestsMatching("*ElectrumMiniWalletTest")
    }
}

// Make NS_FORMAT_ARGUMENT(1) a no-op
// This fixes an issue when building PhoenixCrypto using XCode 13
// More on this: https://youtrack.jetbrains.com/issue/KT-48807#focus=Comments-27-5210791.0-0
tasks.withType(org.jetbrains.kotlin.gradle.tasks.CInteropProcess::class.java) {
    settings.compilerOpts("-DNS_FORMAT_ARGUMENT(A)=")
}
