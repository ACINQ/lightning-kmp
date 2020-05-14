package fr.acinq.eklair

import kotlin.random.Random

import fr.acinq.bitcoin.MnemonicCode

fun generateWordsList(entropy: ByteArray? = null): List<String> {
    var wordslist: kotlin.collections.List<kotlin.String>

    if( entropy != null ) {
        wordslist = MnemonicCode.toMnemonics(entropy)
    } else {
        wordslist = MnemonicCode.toMnemonics(kotlin.random.Random.nextBytes(16))
    }

    return wordslist
}

