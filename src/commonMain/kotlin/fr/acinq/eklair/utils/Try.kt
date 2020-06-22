package fr.acinq.eklair.utils


sealed class Try<out T>(val isSuccess: Boolean) {
    val isFailure: Boolean get() = !isSuccess
    data class Success<out T>(val result: T): Try<T>(true)
    data class Failure<out T>(val error: Throwable): Try<T>(false)
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
