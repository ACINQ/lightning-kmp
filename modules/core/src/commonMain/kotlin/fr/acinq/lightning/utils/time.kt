package fr.acinq.lightning.utils

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
fun currentTimestampMillis(): Long = Clock.System.now().toEpochMilliseconds()

fun currentTimestampSeconds(): Long = currentTimestampMillis() / 1000
