
val buildSsfIosX64 by tasks.creating(Exec::class) {
    group = "build"
    workingDir = projectDir
    commandLine("xcodebuild", "-configuration", "Debug", "build", "-sdk", "iphonesimulator13.6")
}

val buildSsfIosArm64 by tasks.creating(Exec::class) {
    group = "build"
    workingDir = projectDir
    commandLine("xcodebuild", "-configuration", "Debug", "build", "-sdk", "iphoneos13.6")
}
