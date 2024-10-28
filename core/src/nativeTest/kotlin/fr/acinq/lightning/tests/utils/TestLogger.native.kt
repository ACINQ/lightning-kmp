package fr.acinq.lightning.tests.utils

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fprintf

@OptIn(ExperimentalForeignApi::class)
actual fun printToStderr(msg: String) {
    val stderr = platform.posix.fdopen(2, "w")
    fprintf(stderr, msg + "\n")
    fflush(stderr)
}