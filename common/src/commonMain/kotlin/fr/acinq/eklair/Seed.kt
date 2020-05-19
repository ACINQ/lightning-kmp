package fr.acinq.eklair

import kotlin.random.Random
import kotlinx.coroutines.*

import fr.acinq.bitcoin.MnemonicCode

fun runWordsListGeneration(entropy: ByteArray? = null, completion: (List<String>) -> Unit) {
    GlobalScope.launch(context = Dispatchers.Default) {
        var wordslist: List<String>

        if (entropy != null) {
            wordslist = MnemonicCode.toMnemonics(entropy)
        } else {
            wordslist = MnemonicCode.toMnemonics(Random.nextBytes(16))
        }
        
        launch(context = Dispatchers.Main) {
            completion(wordslist)
        }
    }
}
