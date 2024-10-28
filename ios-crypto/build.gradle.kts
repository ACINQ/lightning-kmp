listOf("Iphoneos", "Iphonesimulator").forEach { sdk ->
    tasks.create<Exec>("buildCrypto$sdk") {
        group = "build"

        commandLine(
            "xcodebuild",
            "-quiet",
            "-project", "PhoenixCrypto.xcodeproj",
            "-target", "PhoenixCrypto",
            "-sdk", sdk.lowercase()
        )
        workingDir(projectDir)

        inputs.files(
            fileTree("$projectDir/PhoenixCrypto.xcodeproj") { exclude("**/xcuserdata") },
            fileTree("$projectDir/PhoenixCrypto")
        )
        outputs.files(
            fileTree("$projectDir/build/Release-${sdk.lowercase()}")
        )
    }
}

tasks.create<Delete>("clean") {
    group = "build"

    delete("$projectDir/build")
}
