group = "fr.acinq.eklair"
version = "1.0-SNAPSHOT"

plugins{
    kotlin("multiplatform") version "1.3.70" apply false
}

repositories{
    google()
    jcenter()
}
allprojects {
    configurations.configureEach {
        resolutionStrategy {
            failOnVersionConflict()
            force("org.jetbrains.kotlin:kotlin-stdlib-common:1.3.70",
            "org.jetbrains.kotlin:kotlin-stdlib:1.3.70",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.3.70",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.4",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-common:1.3.4"
            )
        }
    }
}
