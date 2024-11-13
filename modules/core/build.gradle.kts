import org.jetbrains.kotlin.gradle.plugin.mpp.DefaultCInteropSettings
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.dokka)
    `maven-publish`
}

val currentOs = org.gradle.internal.os.OperatingSystem.current()

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

    linuxX64()

    if (currentOs.isMacOsX) {
        macosX64()

        macosArm64()

        fun DefaultCInteropSettings.configureFor(platform: String) {
            val interopTask = tasks[interopProcessingTaskName]
            interopTask.dependsOn(":lightning-kmp-ios-crypto:buildCrypto$platform")
            val libPath = "$rootDir/modules/ios-crypto/build/Release-${platform.lowercase()}"
            extraOpts("-libraryPath", libPath)
            includeDirs.headerFilterOnly("$libPath/include")
        }

        iosX64 { // ios simulator on intel devices
            compilations["main"].cinterops.create("PhoenixCrypto") {
                configureFor("Iphonesimulator")
            }
        }

        iosArm64 { // actual ios devices
            compilations["main"].cinterops.create("PhoenixCrypto") {
                configureFor("Iphoneos")
            }
        }

        iosSimulatorArm64 { // actual ios devices
            compilations["main"].cinterops.create("PhoenixCrypto") {
                configureFor("Iphonesimulator")
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(libs.bitcoinkmp)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.core)
                api(libs.kotlinx.serialization.cbor)
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.network)
                api(libs.ktor.network.tls)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.json)
                implementation(libs.ktor.client.contentnegotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                api(libs.kermit)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("org.kodein.memory:klio-files:0.12.0")
            }
        }

        jvmMain {
            dependencies {
                api(libs.ktor.client.okhttp)
                implementation(libs.secpjnijvm)
                implementation("org.slf4j:slf4j-api:1.7.36")
            }
        }

        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("org.bouncycastle:bcprov-jdk15on:1.64")
                implementation("ch.qos.logback:logback-classic:1.2.3")
                implementation("org.xerial:sqlite-jdbc:3.32.3.3")
            }
        }

        if (currentOs.isMacOsX) {
            iosMain {
                dependencies {
                    implementation(libs.ktor.client.ios)
                }
            }
            macosMain {
                dependencies {
                    implementation(libs.ktor.client.darwin)
                }
            }
        }

        linuxMain {
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }

        all {
            languageSettings.optIn("kotlin.RequiresOptIn")
            languageSettings.optIn("kotlin.ExperimentalStdlibApi")
        }
    }

    configurations.all {
        // do not cache changing (i.e. SNAPSHOT) dependencies
        resolutionStrategy.cacheChangingModulesFor(0, TimeUnit.SECONDS)
    }

    targets.all {
        compilations.all {
            kotlinOptions {
                allWarningsAsErrors = true
                // We use expect/actual for classes (see Chacha20Poly1305CipherFunctions). This feature is in beta and raises a warning.
                // See https://youtrack.jetbrains.com/issue/KT-61573
                kotlinOptions.freeCompilerArgs += "-Xexpect-actual-classes"
            }
        }
    }
}

val dokkaOutputDir = layout.buildDirectory.dir("dokka")
tasks.dokkaHtml {
    outputDirectory.set(file(dokkaOutputDir))
    dokkaSourceSets {
        configureEach {
            val platformName = platform.get().name
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
            compileTaskProvider.get().enabled = false
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

/** Electrum integration test environment + tasks configuration */
val dockerTestEnv by tasks.creating(Exec::class) {
    workingDir = rootDir.resolve("testing")
    commandLine("bash", "env.sh", "remove", "net-create", "btc-create", "elx-create", "btc-start", "elx-start")
}

val dockerCleanup by tasks.creating(Exec::class) {
    workingDir = rootDir.resolve("testing")
    commandLine("bash", "env.sh", "elx-stop", "btc-stop", "remove")
}

val includeIntegrationTests = project.findProperty("integrationTests") == "include"
tasks.withType<AbstractTestTask> {
    if (includeIntegrationTests) {
        dependsOn(dockerTestEnv)
        finalizedBy(dockerCleanup)
    } else {
        filter.excludeTestsMatching("*IntegrationTest")
    }
}

// Those tests use TLS sockets which are not supported on Linux and MacOS
tasks
    .filterIsInstance<KotlinNativeTest>()
    .filter { it.name == "macosX64Test" || it.name == "macosArm64Test" || it.name == "linuxX64Test" }
    .map {
        it.filter.excludeTestsMatching("*IntegrationTest")
        it.filter.excludeTestsMatching("*ElectrumClientTest")
        it.filter.excludeTestsMatching("*ElectrumMiniWalletTest")
        it.filter.excludeTestsMatching("*SwapInWalletTestsCommon")
    }

// Those tests do not work with the ios simulator
tasks
    .filterIsInstance<KotlinNativeSimulatorTest>()
    .map {
        it.filter.excludeTestsMatching("*MempoolSpace*Test")
    }

// Make NS_FORMAT_ARGUMENT(1) a no-op
// This fixes an issue when building PhoenixCrypto using XCode 13
// More on this: https://youtrack.jetbrains.com/issue/KT-48807#focus=Comments-27-5210791.0-0
tasks.withType(org.jetbrains.kotlin.gradle.tasks.CInteropProcess::class.java) {
    settings.compilerOpts("-DNS_FORMAT_ARGUMENT(A)=")
}
