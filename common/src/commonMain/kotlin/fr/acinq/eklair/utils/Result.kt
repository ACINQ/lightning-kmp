package fr.acinq.eklair.utils

sealed class Result<S> {
    data class Success<S>(val result: S): Result<S>()
    data class Failure<S>(val error: Throwable): Result<S>()
}
