package fr.acinq.eklair.utils

expect fun currentTimestampMillis(): Long

fun currentTimestampSeconds(): Long = currentTimestampMillis() / 1000
