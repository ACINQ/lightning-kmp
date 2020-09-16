package fr.acinq.eclair.utils


sealed class Try<out T> {
    abstract val isSuccess: Boolean
    val isFailure: Boolean get() = !isSuccess
    abstract fun get(): T

    data class Success<out T>(val result: T) : Try<T>() {
        override val isSuccess: Boolean = true
        override fun get(): T = result
    }

    data class Failure<out T>(val error: Throwable) : Try<T>() {
        override val isSuccess: Boolean = false
        override fun get(): T {
            throw error
        }
    }
}

inline fun <R> runTrying(block: () -> R): Try<R> =
    try {
        Try.Success(block())
    } catch (e: Throwable) {
        Try.Failure(e)
    }

inline fun <T, R> T.runTrying(block: T.() -> R): Try<R> =
    try {
        Try.Success(block())
    } catch (e: Throwable) {
        Try.Failure(e)
    }
