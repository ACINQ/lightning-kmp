package fr.acinq.eclair.utils

expect fun currentTimestampMillis(): Long

fun currentTimestampSeconds(): Long = currentTimestampMillis() / 1000
