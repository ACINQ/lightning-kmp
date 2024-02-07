package fr.acinq.lightning.tests.utils

actual fun printToStderr(msg: String) {
    System.err.println(msg)
}