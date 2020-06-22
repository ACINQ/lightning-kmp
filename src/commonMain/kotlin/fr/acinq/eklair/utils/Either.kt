package fr.acinq.eklair.utils

sealed class Either<out A, out B> {
    abstract val isLeft: Boolean
    abstract val isRight: Boolean
    abstract val left: A?
    abstract val right: B?

    fun <X> fold(fa: (A) -> X, fb: (B) -> X): X = when (this) {
        is Left -> fa(this.value)
        is Right -> fb(this.value)
    }

    data class Left<out A>(val value: A) : Either<A, Nothing>() {
        override val isLeft = true
        override val isRight = false
        override val left: A? = value
        override val right = null
    }

    data class Right<out B>(val value: B) : Either<Nothing, B>() {
        override val isLeft = false
        override val isRight = true
        override val left = null
        override val right = value
    }
}