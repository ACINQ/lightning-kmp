package fr.acinq.lightning.tests.utils

actual fun readEnvironmentVariable(name: String): String? {
    return System.getenv(name)
}