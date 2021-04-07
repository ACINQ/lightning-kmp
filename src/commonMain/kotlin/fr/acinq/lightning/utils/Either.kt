package fr.acinq.lightning.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = Either.EitherSerializer::class)
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

    @Serializable(with = EitherSerializer::class)
    data class Left<out A, Nothing>(val value: A) : Either<A, Nothing>() {
        override val isLeft = true
        override val isRight = false
        override val left: A? = value
        override val right = null
    }

    @Serializable(with = EitherSerializer::class)
    data class Right<Nothing, out B>(val value: B) : Either<Nothing, B>() {
        override val isLeft = false
        override val isRight = true
        override val left = null
        override val right = value
    }

    class EitherSerializer<A : Any, B : Any>(val aSer: KSerializer<A>, val bSer: KSerializer<B>) :
        KSerializer<Either<A, B>> {

        override val descriptor = buildClassSerialDescriptor("Either", aSer.descriptor, bSer.descriptor) {
            element("left", aSer.descriptor, isOptional = true)
            element("right", bSer.descriptor, isOptional = true)
        }

        override fun serialize(encoder: Encoder, value: Either<A, B>) {
            val compositeEncoder = encoder.beginStructure(descriptor)
            when (value) {
                is Left -> compositeEncoder.encodeSerializableElement(descriptor, 0, aSer, value.value)
                is Right -> compositeEncoder.encodeSerializableElement(descriptor, 1, bSer, value.value)
            }
            compositeEncoder.endStructure(descriptor)
        }

        override fun deserialize(decoder: Decoder): Either<A, B> {
            lateinit var either: Either<A, B>

            val compositeDecoder = decoder.beginStructure(descriptor)
            when (val i = compositeDecoder.decodeElementIndex(descriptor)) {
                0 -> either = Left(compositeDecoder.decodeSerializableElement(descriptor, i, aSer))
                1 -> either = Right(compositeDecoder.decodeSerializableElement(descriptor, i, bSer))
            }
            compositeDecoder.endStructure(descriptor)
            return either
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun <L, R, T> Either<L, R>.flatMap(f: (R) -> Either<L, T>): Either<L, T> =
    this.fold({ this as Either<L, T> }, f)

fun <L, R, T> Either<L, R>.map(f: (R) -> T): Either<L, T> =
    flatMap { Either.Right(f(it)) }