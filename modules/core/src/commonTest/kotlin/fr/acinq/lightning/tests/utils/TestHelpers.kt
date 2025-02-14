package fr.acinq.lightning.tests.utils

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

object TestHelpers {
    val resourcesPath = Path(readEnvironmentVariable("TEST_RESOURCES_PATH")?: "src/commonTest/resources")

    fun readResourceAsString(filename: String): String {
        return SystemFileSystem.source(Path(resourcesPath, filename)).buffered().readString()
    }
}

expect fun readEnvironmentVariable(name: String): String?
