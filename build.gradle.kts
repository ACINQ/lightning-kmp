buildscript {
    val kotlin_version: String by extra
    val gradle_android_version: String by extra

    repositories {
        maven("https://kotlin.bintray.com/kotlinx")
        maven("https://dl.bintray.com/jetbrains/kotlin-native-dependencies")
        maven("https://dl.bintray.com/kotlin/kotlin-dev")

        google()
        jcenter()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version")
        classpath("com.android.tools.build:gradle:$gradle_android_version")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://dl.bintray.com/kotlin/kotlinx")
        maven("https://dl.bintray.com/kotlin/ktor")
        maven("https://dl.bintray.com/sargunster/maven")
        maven("https://dl.bintray.com/kotlin/squash")
        maven("https://dl.bintray.com/kotlin/kotlin-dev")

        google()
        jcenter()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}


//// configuration for the Gradle build itself
//buildscript {
//    ext.kotlin_version = '1.3.71'
//    repositories {
//        google()
//        jcenter()
//    }
//    dependencies {
//        classpath "com.android.tools.build:gradle:3.6.2"
//        classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlin_version"
//
//        // NOTE: Do not place your application dependencies here; they belong
//        // in the individual module build.gradle files
//    }
//}
//
//// configuration for the project's modules
//allprojects {
//    repositories {
//        google()
//        jcenter()
//    }
////    configurations.configureEach {
////        resolutionStrategy {
////            failOnVersionConflict()
////            force("org.jetbrains.kotlin:kotlin-stdlib-common:1.3.71",
////                "org.jetbrains.kotlin:kotlin-stdlib:1.3.71",
////                "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.3.71",
////                "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.4",
////                "org.jetbrains.kotlinx:kotlinx-coroutines-core-common:1.3.4"
////            )
////        }
////    }
//}
//
//task clean(type: Delete) {
//    delete rootProject.buildDir
//}
