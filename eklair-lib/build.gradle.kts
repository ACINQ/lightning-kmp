plugins {
    kotlin("multiplatform") version "1.3.70"
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
    linuxX64("linux")

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
    }

    // Default source set for JVM-specific sources and dependencies:
    jvm().compilations["main"].defaultSourceSet {
        dependencies {
            implementation(kotlin("stdlib-jdk8"))
        }
    }
    // JVM-specific tests and their dependencies:
    jvm().compilations["test"].defaultSourceSet {
        dependencies {
            implementation(kotlin("test-junit"))
            implementation("org.bouncycastle:bcprov-jdk15on:1.64")
        }
    }
}