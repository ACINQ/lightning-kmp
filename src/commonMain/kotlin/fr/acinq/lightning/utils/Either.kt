package fr.acinq.lightning.utils

sealed class Either<out A, out B> {
    abstract val isLeft: Boolean
    abstract val isRight: Boolean
    abstract val left: A?
    abstract val right: B?

    fun <X> fold(fa: (A) -> X, fb: (B) -> X): X = when (this) {
        is Left -> fa(this.value)
        is Right -> fb(this.value)
    }

    fun <X, Y> transform(fa: (A) -> X, fb: (B) -> Y): Either<X, Y> = when (this) {
        is Left -> Left(fa(this.value))
        is Right -> Right(fb(this.value))
    }

    data class Left<out A, Nothing>(val value: A) : Either<A, Nothing>() {
        override val isLeft = true
        override val isRight = false
        override val left: A? = value
        override val right = null
    }

    data class Right<Nothing, out B>(val value: B) : Either<Nothing, B>() {
        override val isLeft = false
        override val isRight = true
        override val left = null
        override val right = value
    }
}

@Suppress("UNCHECKED_CAST")
fun <L, R, T> Either<L, R>.flatMap(f: (R) -> Either<L, T>): Either<L, T> = this.fold({ this as Either<L, T> }, f)

fun <L, R, T> Either<L, R>.map(f: (R) -> T): Either<L, T> = flatMap { Either.Right(f(it)) }

fun <L, R> List<Either<L, R>>.toEither(): Either<L, List<R>> = this.fold(Either.Right(listOf())) { current, element ->
    when (current) {
        is Either.Left -> current
        is Either.Right -> when (element) {
            is Either.Left -> Either.Left(element.value)
            is Either.Right -> Either.Right(current.value + element.value)
        }
    }
}