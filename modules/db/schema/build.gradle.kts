plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.sqldelight)
}

val currentOs = org.gradle.internal.os.OperatingSystem.current()
val arch = System.getProperty("os.arch")

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
    }

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
            }
        }
    }
}

sqldelight {
    databases {
        create("SqlitePaymentsDb") {
            this.packageName.set("fr.acinq.lightning.db.sqlite")
            srcDirs.from("src/sqldelight/paymentsdb")
        }
    }
}
