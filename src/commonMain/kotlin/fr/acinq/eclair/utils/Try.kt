package fr.acinq.eclair.utils


sealed class Try<T> {
    abstract val isSuccess: Boolean
    val isFailure: Boolean get() = !isSuccess
    abstract fun get(): T
    abstract fun getOrElse(f: () -> T): T

    data class Success<T>(val result: T) : Try<T>() {
        override val isSuccess: Boolean = true
        override fun get(): T = result
        override fun getOrElse(f: () -> T): T = result
    }

    data class Failure<T>(val error: Throwable) : Try<T>() {
        override val isSuccess: Boolean = false
        override fun get(): T {
            throw error
        }

        override fun getOrElse(f: () -> T): T = f()
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
