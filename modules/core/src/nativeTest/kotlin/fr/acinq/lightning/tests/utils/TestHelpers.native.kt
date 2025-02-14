package fr.acinq.lightning.tests.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun readEnvironmentVariable(name: String): String? {
    return getenv(name)?.toKString()
}