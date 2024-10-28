/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.acinq.lightning.crypto.noise

import java.util.*

/**
 * Implementation of the Curve25519 elliptic curve algorithm.
 *
 *
 * This implementation is based on that from arduinolibs:
 * https://github.com/rweather/arduinolibs
 *
 *
 * Differences in this version are due to using 26-bit limbs for the
 * representation instead of the 8/16/32-bit limbs in the original.
 *
 *
 * References: http://cr.yp.to/ecdh.html, RFC 7748
 */
class Curve25519 private constructor() {
    private val x_1: IntArray
    private val x_2: IntArray
    private val x_3: IntArray
    private val z_2: IntArray
    private val z_3: IntArray
    private val A: IntArray
    private val B: IntArray
    private val C: IntArray
    private val D: IntArray
    private val E: IntArray
    private val AA: IntArray
    private val BB: IntArray
    private val DA: IntArray
    private val CB: IntArray
    private val t1: LongArray
    private val t2: IntArray

    /**
     * Constructs the temporary state holder for Curve25519 evaluation.
     */
    init {
        // Allocate memory for all of the temporary variables we will need.
        x_1 = IntArray(NUM_LIMBS_255BIT)
        x_2 = IntArray(NUM_LIMBS_255BIT)
        x_3 = IntArray(NUM_LIMBS_255BIT)
        z_2 = IntArray(NUM_LIMBS_255BIT)
        z_3 = IntArray(NUM_LIMBS_255BIT)
        A = IntArray(NUM_LIMBS_255BIT)
        B = IntArray(NUM_LIMBS_255BIT)
        C = IntArray(NUM_LIMBS_255BIT)
        D = IntArray(NUM_LIMBS_255BIT)
        E = IntArray(NUM_LIMBS_255BIT)
        AA = IntArray(NUM_LIMBS_255BIT)
        BB = IntArray(NUM_LIMBS_255BIT)
        DA = IntArray(NUM_LIMBS_255BIT)
        CB = IntArray(NUM_LIMBS_255BIT)
        t1 = LongArray(NUM_LIMBS_510BIT)
        t2 = IntArray(NUM_LIMBS_510BIT)
    }


    /**
     * Destroy all sensitive data in this object.
     */
    private fun destroy() {
        // Destroy all temporary variables.
        Arrays.fill(x_1, 0)
        Arrays.fill(x_2, 0)
        Arrays.fill(x_3, 0)
        Arrays.fill(z_2, 0)
        Arrays.fill(z_3, 0)
        Arrays.fill(A, 0)
        Arrays.fill(B, 0)
        Arrays.fill(C, 0)
        Arrays.fill(D, 0)
        Arrays.fill(E, 0)
        Arrays.fill(AA, 0)
        Arrays.fill(BB, 0)
        Arrays.fill(DA, 0)
        Arrays.fill(CB, 0)
        Arrays.fill(t1, 0L)
        Arrays.fill(t2, 0)
    }

    /**
     * Reduces a number modulo 2^255 - 19 where it is known that the
     * number can be reduced with only 1 trial subtraction.
     *
     * @param x The number to reduce, and the result.
     */
    private fun reduceQuick(x: IntArray) {
        var carry: Int

        // Perform a trial subtraction of (2^255 - 19) from "x" which is
        // equivalent to adding 19 and subtracting 2^255.  We add 19 here;
        // the subtraction of 2^255 occurs in the next step.
        carry = 19
        var index = 0
        while (index < NUM_LIMBS_255BIT) {
            carry += x[index]
            t2[index] = carry and 0x03FFFFFF
            carry = carry shr 26
            ++index
        }

        // If there was a borrow, then the original "x" is the correct answer.
        // If there was no borrow, then "t2" is the correct answer.  Select the
        // correct answer but do it in a way that instruction timing will not
        // reveal which value was selected.  Borrow will occur if bit 21 of
        // "t2" is zero.  Turn the bit into a selection mask.
        val mask = -((t2[NUM_LIMBS_255BIT - 1] shr 21) and 0x01)
        val nmask = mask.inv()
        t2[NUM_LIMBS_255BIT - 1] = t2[NUM_LIMBS_255BIT - 1] and 0x001FFFFF
        index = 0
        while (index < NUM_LIMBS_255BIT) {
            x[index] = (x[index] and nmask) or (t2[index] and mask)
            ++index
        }
    }

    /**
     * Reduce a number modulo 2^255 - 19.
     *
     * @param result The result.
     * @param x      The value to be reduced.  This array will be
     * modified during the reduction.
     * @param size   The number of limbs in the high order half of x.
     */
    private fun reduce(result: IntArray, x: IntArray, size: Int) {
        var index: Int
        var limb: Int
        var carry: Int

        // Calculate (x mod 2^255) + ((x / 2^255) * 19) which will
        // either produce the answer we want or it will produce a
        // value of the form "answer + j * (2^255 - 19)".  There are
        // 5 left-over bits in the top-most limb of the bottom half.
        carry = 0
        limb = x[NUM_LIMBS_255BIT - 1] shr 21
        x[NUM_LIMBS_255BIT - 1] = x[NUM_LIMBS_255BIT - 1] and 0x001FFFFF
        index = 0
        while (index < size) {
            limb += x[NUM_LIMBS_255BIT + index] shl 5
            carry += (limb and 0x03FFFFFF) * 19 + x[index]
            x[index] = carry and 0x03FFFFFF
            limb = limb shr 26
            carry = carry shr 26
            ++index
        }
        if (size < NUM_LIMBS_255BIT) {
            // The high order half of the number is short; e.g. for mulA24().
            // Propagate the carry through the rest of the low order part.
            index = size
            while (index < NUM_LIMBS_255BIT) {
                carry += x[index]
                x[index] = carry and 0x03FFFFFF
                carry = carry shr 26
                ++index
            }
        }

        // The "j" value may still be too large due to the final carry-out.
        // We must repeat the reduction.  If we already have the answer,
        // then this won't do any harm but we must still do the calculation
        // to preserve the overall timing.  The "j" value will be between
        // 0 and 19, which means that the carry we care about is in the
        // top 5 bits of the highest limb of the bottom half.
        carry = (x[NUM_LIMBS_255BIT - 1] shr 21) * 19
        x[NUM_LIMBS_255BIT - 1] = x[NUM_LIMBS_255BIT - 1] and 0x001FFFFF
        index = 0
        while (index < NUM_LIMBS_255BIT) {
            carry += x[index]
            result[index] = carry and 0x03FFFFFF
            carry = carry shr 26
            ++index
        }

        // At this point "x" will either be the answer or it will be the
        // answer plus (2^255 - 19).  Perform a trial subtraction to
        // complete the reduction process.
        reduceQuick(result)
    }

    /**
     * Multiplies two numbers modulo 2^255 - 19.
     *
     * @param result The result.
     * @param x      The first number to multiply.
     * @param y      The second number to multiply.
     */
    private fun mul(result: IntArray, x: IntArray, y: IntArray) {
        var j: Int

        // Multiply the two numbers to create the intermediate result.
        var v = x[0].toLong()
        var i = 0
        while (i < NUM_LIMBS_255BIT) {
            t1[i] = v * y[i]
            ++i
        }
        i = 1
        while (i < NUM_LIMBS_255BIT) {
            v = x[i].toLong()
            j = 0
            while (j < (NUM_LIMBS_255BIT - 1)) {
                t1[i + j] += v * y[j]
                ++j
            }
            t1[i + NUM_LIMBS_255BIT - 1] = v * y[NUM_LIMBS_255BIT - 1]
            ++i
        }

        // Propagate carries and convert back into 26-bit words.
        v = t1[0]
        t2[0] = (v.toInt()) and 0x03FFFFFF
        i = 1
        while (i < NUM_LIMBS_510BIT) {
            v = (v shr 26) + t1[i]
            t2[i] = (v.toInt()) and 0x03FFFFFF
            ++i
        }

        // Reduce the result modulo 2^255 - 19.
        reduce(result, t2, NUM_LIMBS_255BIT)
    }

    /**
     * Squares a number modulo 2^255 - 19.
     *
     * @param result The result.
     * @param x      The number to square.
     */
    private fun square(result: IntArray, x: IntArray) {
        mul(result, x, x)
    }

    /**
     * Multiplies a number by the a24 constant, modulo 2^255 - 19.
     *
     * @param result The result.
     * @param x      The number to multiply by a24.
     */
    private fun mulA24(result: IntArray, x: IntArray) {
        val a24: Long = 121665
        var carry: Long = 0
        var index = 0
        while (index < NUM_LIMBS_255BIT) {
            carry += a24 * x[index]
            t2[index] = (carry.toInt()) and 0x03FFFFFF
            carry = carry shr 26
            ++index
        }
        t2[NUM_LIMBS_255BIT] = (carry.toInt()) and 0x03FFFFFF
        reduce(result, t2, 1)
    }

    /**
     * Adds two numbers modulo 2^255 - 19.
     *
     * @param result The result.
     * @param x      The first number to add.
     * @param y      The second number to add.
     */
    private fun add(result: IntArray, x: IntArray, y: IntArray) {
        var carry: Int
        carry = x[0] + y[0]
        result[0] = carry and 0x03FFFFFF
        var index = 1
        while (index < NUM_LIMBS_255BIT) {
            carry = (carry shr 26) + x[index] + y[index]
            result[index] = carry and 0x03FFFFFF
            ++index
        }
        reduceQuick(result)
    }

    /**
     * Subtracts two numbers modulo 2^255 - 19.
     *
     * @param result The result.
     * @param x      The first number to subtract.
     * @param y      The second number to subtract.
     */
    private fun sub(result: IntArray, x: IntArray, y: IntArray) {
        var borrow: Int

        // Subtract y from x to generate the intermediate result.
        borrow = 0
        var index = 0
        while (index < NUM_LIMBS_255BIT) {
            borrow = x[index] - y[index] - ((borrow shr 26) and 0x01)
            result[index] = borrow and 0x03FFFFFF
            ++index
        }

        // If we had a borrow, then the result has gone negative and we
        // have to add 2^255 - 19 to the result to make it positive again.
        // The top bits of "borrow" will be all 1's if there is a borrow
        // or it will be all 0's if there was no borrow.  Easiest is to
        // conditionally subtract 19 and then mask off the high bits.
        borrow = result[0] - ((-((borrow shr 26) and 0x01)) and 19)
        result[0] = borrow and 0x03FFFFFF
        index = 1
        while (index < NUM_LIMBS_255BIT) {
            borrow = result[index] - ((borrow shr 26) and 0x01)
            result[index] = borrow and 0x03FFFFFF
            ++index
        }
        result[NUM_LIMBS_255BIT - 1] = result[NUM_LIMBS_255BIT - 1] and 0x001FFFFF
    }

    /**
     * Raise x to the power of (2^250 - 1).
     *
     * @param result The result.  Must not overlap with x.
     * @param x      The argument.
     */
    private fun pow250(result: IntArray, x: IntArray) {
        var j: Int

        // The big-endian hexadecimal expansion of (2^250 - 1) is:
        // 03FFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF
        //
        // The naive implementation needs to do 2 multiplications per 1 bit and
        // 1 multiplication per 0 bit.  We can improve upon this by creating a
        // pattern 0000000001 ... 0000000001.  If we square and multiply the
        // pattern by itself we can turn the pattern into the partial results
        // 0000000011 ... 0000000011, 0000000111 ... 0000000111, etc.
        // This averages out to about 1.1 multiplications per 1 bit instead of 2.

        // Build a pattern of 250 bits in length of repeated copies of 0000000001.
        square(A, x)
        j = 0
        while (j < 9) {
            square(A, A)
            ++j
        }
        mul(result, A, x)
        var i = 0
        while (i < 23) {
            j = 0
            while (j < 10) {
                square(A, A)
                ++j
            }
            mul(result, result, A)
            ++i
        }

        // Multiply bit-shifted versions of the 0000000001 pattern into
        // the result to "fill in" the gaps in the pattern.
        square(A, result)
        mul(result, result, A)
        j = 0
        while (j < 8) {
            square(A, A)
            mul(result, result, A)
            ++j
        }
    }

    /**
     * Computes the reciprocal of a number modulo 2^255 - 19.
     *
     * @param result The result.  Must not overlap with x.
     * @param x      The argument.
     */
    private fun recip(result: IntArray, x: IntArray) {
        // The reciprocal is the same as x ^ (p - 2) where p = 2^255 - 19.
        // The big-endian hexadecimal expansion of (p - 2) is:
        // 7FFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFFF FFFFFFEB
        // Start with the 250 upper bits of the expansion of (p - 2).
        pow250(result, x)

        // Deal with the 5 lowest bits of (p - 2), 01011, from highest to lowest.
        square(result, result)
        square(result, result)
        mul(result, result, x)
        square(result, result)
        square(result, result)
        mul(result, result, x)
        square(result, result)
        mul(result, result, x)
    }

    /**
     * Evaluates the curve for every bit in a secret key.
     *
     * @param s The 32-byte secret key.
     */
    private fun evalCurve(s: ByteArray) {
        var sposn = 31
        var sbit = 6
        var svalue = s[sposn].toInt() or 0x40
        var swap = 0
        var select: Int

        // Iterate over all 255 bits of "s" from the highest to the lowest.
        // We ignore the high bit of the 256-bit representation of "s".
        while (true) {
            // Conditional swaps on entry to this bit but only if we
            // didn't swap on the previous bit.
            select = (svalue shr sbit) and 0x01
            swap = swap xor select
            cswap(swap, x_2, x_3)
            cswap(swap, z_2, z_3)
            swap = select

            // Evaluate the curve.
            add(A, x_2, z_2) // A = x_2 + z_2
            square(AA, A) // AA = A^2
            sub(B, x_2, z_2) // B = x_2 - z_2
            square(BB, B) // BB = B^2
            sub(E, AA, BB) // E = AA - BB
            add(C, x_3, z_3) // C = x_3 + z_3
            sub(D, x_3, z_3) // D = x_3 - z_3
            mul(DA, D, A) // DA = D * A
            mul(CB, C, B) // CB = C * B
            add(x_3, DA, CB) // x_3 = (DA + CB)^2
            square(x_3, x_3)
            sub(z_3, DA, CB) // z_3 = x_1 * (DA - CB)^2
            square(z_3, z_3)
            mul(z_3, z_3, x_1)
            mul(x_2, AA, BB) // x_2 = AA * BB
            mulA24(z_2, E) // z_2 = E * (AA + a24 * E)
            add(z_2, z_2, AA)
            mul(z_2, z_2, E)

            // Move onto the next lower bit of "s".
            if (sbit > 0) {
                --sbit
            } else if (sposn == 0) {
                break
            } else if (sposn == 1) {
                --sposn
                svalue = s[sposn].toInt() and 0xF8
                sbit = 7
            } else {
                --sposn
                svalue = s[sposn].toInt()
                sbit = 7
            }
        }

        // Final conditional swaps.
        cswap(swap, x_2, x_3)
        cswap(swap, z_2, z_3)
    }

    companion object {
        // Numbers modulo 2^255 - 19 are broken up into ten 26-bit words.
        private const val NUM_LIMBS_255BIT = 10
        private const val NUM_LIMBS_510BIT = 20

        /**
         * Conditional swap of two values.
         *
         * @param select Set to 1 to swap, 0 to leave as-is.
         * @param x      The first value.
         * @param y      The second value.
         */
        private fun cswap(select: Int, x: IntArray, y: IntArray) {
            var select1 = select
            var dummy: Int
            select1 = -select1
            for (index in 0..<NUM_LIMBS_255BIT) {
                dummy = select1 and (x[index] xor y[index])
                x[index] = x[index] xor dummy
                y[index] = y[index] xor dummy
            }
        }

        /**
         * Evaluates the Curve25519 curve.
         *
         * @param result     Buffer to place the result of the evaluation into.
         * @param offset     Offset into the result buffer.
         * @param privateKey The private key to use in the evaluation.
         * @param publicKey  The public key to use in the evaluation, or null
         * if the base point of the curve should be used.
         */
        fun eval(result: ByteArray, offset: Int, privateKey: ByteArray, publicKey: ByteArray?) {
            val state = Curve25519()
            try {
                // Unpack the public key value.  If null, use 9 as the base point.
                Arrays.fill(state.x_1, 0)
                if (publicKey != null) {
                    // Convert the input value from little-endian into 26-bit limbs.
                    for (index in 0..31) {
                        val bit = (index * 8) % 26
                        val word = (index * 8) / 26
                        val value = publicKey[index].toInt() and 0xFF
                        if (bit <= (26 - 8)) {
                            state.x_1[word] = state.x_1[word] or (value shl bit)
                        } else {
                            state.x_1[word] = state.x_1[word] or (value shl bit)
                            state.x_1[word] = state.x_1[word] and 0x03FFFFFF
                            state.x_1[word + 1] = state.x_1[word + 1] or (value shr (26 - bit))
                        }
                    }

                    // Just in case, we reduce the number modulo 2^255 - 19 to
                    // make sure that it is in range of the field before we start.
                    // This eliminates values between 2^255 - 19 and 2^256 - 1.
                    state.reduceQuick(state.x_1)
                    state.reduceQuick(state.x_1)
                } else {
                    state.x_1[0] = 9
                }

                // Initialize the other temporary variables.
                Arrays.fill(state.x_2, 0) // x_2 = 1
                state.x_2[0] = 1
                Arrays.fill(state.z_2, 0) // z_2 = 0
                System.arraycopy(state.x_1, 0, state.x_3, 0, state.x_1.size) // x_3 = x_1
                Arrays.fill(state.z_3, 0) // z_3 = 1
                state.z_3[0] = 1

                // Evaluate the curve for every bit of the private key.
                state.evalCurve(privateKey)

                // Compute x_2 * (z_2 ^ (p - 2)) where p = 2^255 - 19.
                state.recip(state.z_3, state.z_2)
                state.mul(state.x_2, state.x_2, state.z_3)

                // Convert x_2 into little-endian in the result buffer.
                for (index in 0..31) {
                    val bit = (index * 8) % 26
                    val word = (index * 8) / 26
                    if (bit <= (26 - 8)) result[offset + index] = (state.x_2[word] shr bit).toByte()
                    else result[offset + index] = ((state.x_2[word] shr bit) or (state.x_2[word + 1] shl (26 - bit))).toByte()
                }
            } finally {
                // Clean up all temporary state before we exit.
                state.destroy()
            }
        }
    }
}