package fr.acinq.eklair

import platform.UIKit.UIDevice

import kotlinx.coroutines.*

import kotlin.random.Random

import fr.acinq.eklair.crypto.Sha256

import fr.acinq.bitcoin.MnemonicCode

fun platformName(): String {
    return UIDevice.currentDevice.systemName() + " " +  UIDevice.currentDevice.systemVersion
}

fun hash(value: String): String {
    return Hex.encode(Sha256.hash(value.encodeToByteArray()))
}

//fun generateWordList(entropy: ByteArray? = null): List<String> {
fun generateWordList(): List<String> {
    val entropy = kotlin.random.Random.nextBytes(16)
    val wordlist = MnemonicCode.toMnemonics(entropy)

    return wordlist
}
