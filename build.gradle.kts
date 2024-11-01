plugins {
    alias(libs.plugins.multiplatform) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.sqldelight) apply false
    `maven-publish`
}