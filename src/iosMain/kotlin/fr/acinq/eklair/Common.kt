package fr.acinq.eklair

import platform.UIKit.UIDevice

import kotlinx.coroutines.*

import fr.acinq.eklair.crypto.Sha256

fun platformName(): String {
    return UIDevice.currentDevice.systemName() + " " +  UIDevice.currentDevice.systemVersion
}

fun hash(value: String): String {
    return Hex.encode(Sha256.hash(value.encodeToByteArray()))
}
