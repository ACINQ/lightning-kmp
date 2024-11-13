package fr.acinq.lightning.utils

import kotlinx.datetime.Clock

fun currentTimestampMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun currentTimestampSeconds(): Long = currentTimestampMillis() / 1000
