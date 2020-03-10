plugins {
    application
    kotlin("multiplatform") version "1.3.70"
}

group = "fr.acinq.eklair"
version = "1.0-SNAPSHOT"

application {
    mainClassName = "fr.acinq.eklair.Boot"
}

repositories {
    mavenCentral()
}

kotlin {
    /* Targets configuration omitted.
    *  To find out how to configure the targets, please follow the link:
    *  https://kotlinlang.org/docs/reference/building-mpp-with-gradle.html#setting-up-targets */

    jvm() {

    }
    linuxX64("linux") {
        binaries {
            executable()
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation("io.ktor:ktor-client-core:1.3.1")
                implementation("io.ktor:ktor-network:1.3.1")
                implementation(project(":eklair-lib"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
    }

    // Default source set for JVM-specific sources and dependencies:
    jvm().compilations["main"].defaultSourceSet {
        dependencies {
            implementation(kotlin("stdlib-jdk8"))
            implementation("io.ktor:ktor-client-okhttp:1.3.1")
        }
    }
    // JVM-specific tests and their dependencies:
    jvm().compilations["test"].defaultSourceSet {
        dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}
