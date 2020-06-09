package fr.acinq.eklair

import kotlin.test.assertTrue


inline fun <T, reified R : T> T.assertIs() = assertTrue(this is R)
