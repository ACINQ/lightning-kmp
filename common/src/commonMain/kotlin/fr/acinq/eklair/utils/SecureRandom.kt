package fr.acinq.eklair.utils

import kotlin.random.Random

// https://github.com/Kotlin/KEEP/issues/184
expect fun Random.Default.secure(): Random
