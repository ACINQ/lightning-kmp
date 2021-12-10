package fr.acinq.lightning.utils

import fr.acinq.lightning.tests.utils.LightningTestSuite
import kotlin.test.Test
import kotlin.test.assertEquals

class EitherTestsCommon : LightningTestSuite() {

    @Test
    fun `common operations on either`() {
        assertEquals(Either.Left<Int, String>(1).fold({ it + 1 }, { 10 }), 2)
        assertEquals(Either.Right<Int, String>("test").fold({ it + 1 }, { 10 }), 10)

        assertEquals(Either.Left<Int, String>(1).transform({ it.toString() }, { it.toInt() }), Either.Left("1"))
        assertEquals(Either.Right<Int, String>("1").transform({ it.toString() }, { it.toInt() }), Either.Right(1))

        assertEquals(Either.Left<Int, String>(1).map { it + "_updated" }, Either.Left(1))
        assertEquals(Either.Right<Int, String>("value").map { it + "_updated" }, Either.Right("value_updated"))

        assertEquals(Either.Left<Int, String>(1).flatMap { v -> if (v.length <= 5) Either.Right(v) else Either.Left("too long") }, Either.Left(1))
        assertEquals(Either.Right<Int, String>("abc").flatMap { v -> if (v.length <= 5) Either.Right(v) else Either.Left("too long") }, Either.Right("abc"))
        assertEquals(Either.Right<Int, String>("abcdef").flatMap { v -> if (v.length <= 5) Either.Right(v) else Either.Left("too long") }, Either.Left("too long"))

        assertEquals(listOf<Either<Int, String>>(Either.Left(2), Either.Right("3")).toEither(), Either.Left(2))
        assertEquals(listOf<Either<Int, String>>(Either.Right("2"), Either.Right("3")).toEither(), Either.Right(listOf("2", "3")))
    }

}