package fr.acinq.lightning.utils

expect fun currentTimestampMillis(): Long

fun currentTimestampSeconds(): Long = currentTimestampMillis() / 1000
