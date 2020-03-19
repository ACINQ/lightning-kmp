package fr.acinq.eklair

import com.benasher44.uuid.Uuid
import com.benasher44.uuid.uuidFrom
import fr.acinq.eklair.crypto.Sha256

data class EklairDTO(val message: String) {
    fun hashValues(): String = Hex.encode(Sha256.hash(message.encodeToByteArray()))
}

data class EklairUser(val id: Uuid)

data class MessageContainer(val message: String, val counter: Int, val identity: EklairUser)

object MessageLogger{
    fun log(msg: MessageContainer){
        println("[${msg.message}] - ${msg.identity.id} : ${msg.counter}")
    }

    fun nativeLog(closure: () -> String){
        println(closure())
    }
}

object Uuid {
    fun fromString(s: String): Uuid {
        return uuidFrom(s)
    }
}

