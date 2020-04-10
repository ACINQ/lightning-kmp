package fr.acinq.eklair

import kotlinx.coroutines.*

expect fun platformName(): String

expect fun hash(value: String): String

//fun withActor(message: (EklairActorMessage?) -> Any) {
//    runBlocking<Unit> {
//        delay(2000)
//    }
//}
