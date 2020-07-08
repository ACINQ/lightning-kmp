package fr.acinq.eklair.transactions

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eklair.CltvExpiry
import fr.acinq.eklair.TestConstants
import fr.acinq.eklair.utils.msat
import fr.acinq.eklair.utils.sat
import fr.acinq.eklair.utils.toMilliSatoshi
import fr.acinq.eklair.wire.UpdateAddHtlc
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.utils.io.core.use
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TransactionTestsJvm {

    // This test is JVM only until both these issues have been resolved:
    // - https://youtrack.jetbrains.com/issue/KT-39789
    // - https://github.com/ktorio/ktor/issues/1964
    @Test fun `BOLT 2 fee tests`() {
        runBlocking {
            val bolt3 = HttpClient().use { client ->
                client.get<String>("https://raw.githubusercontent.com/lightningnetwork/lightning-rfc/master/03-transactions.md")
                    .replace("    name:", "$   name:")
                // character '$' separates tests
            }

            // this regex extract params from a given test
            val testRegex = Regex(
                """name: (.*)\n""" +
                        """.*to_local_msat: ([0-9]+)\n""" +
                        """.*to_remote_msat: ([0-9]+)\n""" +
                        """.*feerate_per_kw: ([0-9]+)\n""" +
                        """.*base commitment transaction fee = ([0-9]+)\n""" +
                        """[^$]+"""
            )

            // this regex extracts htlc direction and amounts
            val htlcRegex = Regex(""".*HTLC [0-9] ([a-z]+) amount ([0-9]+).*""")

            val dustLimit = 546.sat
            data class TestSetup(val name: String, val dustLimit: Satoshi, val spec: CommitmentSpec, val expectedFee: Satoshi)

            val tests = testRegex.findAll(bolt3).map { s ->
                val (name, to_local_msat, to_remote_msat, feerate_per_kw, fee) = s.destructured
                val htlcs = htlcRegex.findAll(s.value).map { l ->
                    val (direction, amount) = l.destructured
                    when (direction) {
                        "offered" -> OutgoingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, amount.toLong().sat.toMilliSatoshi(), ByteVector32.Zeroes, CltvExpiry(144), TestConstants.emptyOnionPacket))
                        "received" -> IncomingHtlc(UpdateAddHtlc(ByteVector32.Zeroes, 0, amount.toLong().sat.toMilliSatoshi(), ByteVector32.Zeroes, CltvExpiry(144), TestConstants.emptyOnionPacket))
                        else -> error("Unknown direction $direction")
                    }
                } .toSet()
                TestSetup(name, dustLimit, CommitmentSpec(htlcs, feerate_per_kw.toLong(), to_local_msat.toLong().msat, to_remote_msat.toLong().msat), fee.toLong().sat)
            }.toList()

            // simple non-reg test making sure we are not missing tests
            assertEquals(15, tests.size, "there were 15 tests at ec99f893f320e8c88f564c1c8566f3454f0f1f5f")

            tests.forEach { test ->
                val fee = Transactions.commitTxFee(test.dustLimit, test.spec)
                assertEquals(test.expectedFee, fee, "In '${test.name}'")
            }
        }
    }

}
