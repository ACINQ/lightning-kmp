plugins {
    kotlin("multiplatform")
    `maven-publish`
}

val currentOs = org.gradle.internal.os.OperatingSystem.current()

kotlin {
    val ktorVersion: String by rootProject.extra
    fun ktor(module: String) = "io.ktor:ktor-$module:$ktorVersion"

    val commonMain by sourceSets.getting {
        dependencies {
            implementation(rootProject)

            api(ktor("client-core"))
            api(ktor("client-auth"))
            api(ktor("client-json"))
            api(ktor("client-serialization"))

            implementation(kotlin("test-common"))
            implementation(kotlin("test-annotations-common"))
        }
    }

    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        compilations["main"].defaultSourceSet.dependencies {
        }
    }

    val nativeMain by sourceSets.creating { dependsOn(commonMain) }

    if (currentOs.isLinux) {
        linuxX64("linux") {
            compilations["main"].defaultSourceSet.dependsOn(nativeMain)
        }
    }

    if (currentOs.isMacOsX) {
        ios {
            compilations["main"].defaultSourceSet.dependsOn(nativeMain)
        }
    }

    sourceSets.all {
        languageSettings.useExperimentalAnnotation("kotlin.RequiresOptIn")
    }
}

