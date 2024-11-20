plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    `maven-publish`
}

val currentOs = org.gradle.internal.os.OperatingSystem.current()
val arch = System.getProperty("os.arch")

kotlin {

    jvm()

    if (currentOs.isLinux && arch != "aarch64") {
        linuxX64()
    }

    if (currentOs.isMacOsX) {
        macosX64()

        macosArm64()
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":lightning-kmp-core"))
                implementation(libs.sqldelight.runtime)
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.kotlinx.coroutinesTest)
            }
        }
        jvmTest {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation("ch.qos.logback:logback-classic:${libs.versions.test.logback.get()}")
                implementation(libs.sqldelight.sqliteDriver)
            }
        }
        nativeTest {
            dependencies {
               implementation(libs.sqldelight.nativeDriver)
            }
        }
    }
}
