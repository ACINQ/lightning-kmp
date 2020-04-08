package fr.acinq.eklair

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/* TODO: Maybe Channel.DEFAULT here */
@InternalCoroutinesApi
public fun <E> CoroutineScope.actor(context: CoroutineContext = EmptyCoroutineContext, capacity: Int = 0, start: CoroutineStart = CoroutineStart.DEFAULT, onCompletion: CompletionHandler? = null, block: suspend ActorScope<E>.() -> Unit): SendChannel<E> {
    val newContext = newCoroutineContext(context)
    val channel = Channel<E>(capacity)
    val coroutine = ActorCoroutine(newContext, channel)
    //if (onCompletion != null) coroutine.invokeOnCompletion(handler = onCompletion)
    coroutine.start(start, coroutine, block)
    return coroutine
}


interface ActorScope<E> : CoroutineScope, ReceiveChannel<E> {
    val channel: Channel<E>
}

@InternalCoroutinesApi
class ActorCoroutine<E>(parentContext: CoroutineContext, protected val _channel: Channel<E>) : AbstractCoroutine<Unit>(parentContext), ActorScope<E>, Channel<E> by _channel {
    override val channel: Channel<E> get() = this

    override fun cancel() {
        cancelInternal(CancellationException("Cancelling"))
    }

    override fun cancelInternal(cause: Throwable) {
        val exception = cause.toCancellationException()
        _channel.cancel(exception) // cancel the channel
        cancelCoroutine(exception) // cancel the job
    }

    override fun cancel(cause: CancellationException?) {
        _channel.cancel(cause)
    }

    override fun cancel(cause: Throwable?): Boolean {
        (cause as? CancellationException)?.let {
            _channel.cancel(it)
            return true
        }
        return false
    }
}
