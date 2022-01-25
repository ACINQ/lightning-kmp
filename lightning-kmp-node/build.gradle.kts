import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.squareup.sqldelight:gradle-plugin:1.5.2")
    }
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.squareup.sqldelight") version "1.5.2"
    application
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(rootProject)
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.6.0")
    implementation("org.bouncycastle:bcprov-jdk15on:1.69")
    implementation("org.codehaus.janino:janino:3.1.6")
    implementation("org.slf4j:slf4j-api:1.7.33")
    implementation("ch.qos.logback:logback-classic:1.2.10")
    implementation("io.ktor:ktor-server-netty:1.6.7")
    implementation("io.ktor:ktor-serialization:1.6.7")
    implementation("com.typesafe:config:1.4.1")
    implementation("com.squareup.sqldelight:sqlite-driver:1.5.2")
    implementation("com.squareup.sqldelight:runtime:1.5.2")
    implementation("com.squareup.sqldelight:coroutines-extensions:1.5.2")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("fr.acinq.phoenix.Node")
}

sqldelight {
    database("ChannelsDatabase") {
        packageName = "fr.acinq.phoenix.db"
        sourceFolders = listOf("sqldelight/channelsdb")
    }
    database("PaymentsDatabase") {
        packageName = "fr.acinq.phoenix.db"
        sourceFolders = listOf("sqldelight/paymentsdb")
    }
}