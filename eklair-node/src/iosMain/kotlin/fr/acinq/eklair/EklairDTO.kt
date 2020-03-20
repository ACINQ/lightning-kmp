package fr.acinq.eklair

import fr.acinq.eklair.crypto.Sha256

data class EklairDTO(val message: String) {
    fun hashValues(): String = Hex.encode(Sha256.hash(message.encodeToByteArray()))
}

data class EklairUser(val id: String)

data class MessageContainer(val message: String, val counter: Int, val identity: EklairUser)

object MessageLogger{
    fun log(msg: MessageContainer){
        println("[${msg.message}] - ${msg.identity.id} : ${msg.counter}")
    }

    fun nativeLog(closure: () -> String){
        println(closure())
    }
}

