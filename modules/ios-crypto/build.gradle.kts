// macOS doesn't get an "-$(EFFECTIVE_PLATFORM_NAME)" suffix on its build dir like the iOS SDKs
// do, so CONFIGURATION_BUILD_DIR is set explicitly for it, to keep the Release-<sdk> convention
// that the cinterop config in modules/core/build.gradle.kts relies on for every platform.
mapOf(
    "Iphoneos" to listOf("-sdk", "iphoneos"),
    "Iphonesimulator" to listOf("-sdk", "iphonesimulator"),
    "Macosx" to listOf("-sdk", "macosx", "MACOSX_DEPLOYMENT_TARGET=11.0", "CONFIGURATION_BUILD_DIR=$projectDir/build/Release-macosx"),
).forEach { (label, sdkArgs) ->
    tasks.create<Exec>("buildCrypto$label") {
        group = "build"

        commandLine(
            listOf(
                "xcodebuild",
                "-quiet",
                "-project", "PhoenixCrypto.xcodeproj",
                "-target", "PhoenixCrypto",
            ) + sdkArgs
        )
        workingDir(projectDir)

        inputs.files(
            fileTree("$projectDir/PhoenixCrypto.xcodeproj") { exclude("**/xcuserdata") },
            fileTree("$projectDir/PhoenixCrypto")
        )
        outputs.files(
            fileTree("$projectDir/build/Release-${label.lowercase()}")
        )
    }
}

tasks.create<Delete>("clean") {
    group = "build"

    delete("$projectDir/build")
}
