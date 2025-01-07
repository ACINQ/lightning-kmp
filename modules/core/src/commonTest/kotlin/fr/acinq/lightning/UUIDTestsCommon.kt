package fr.acinq.lightning

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.lightning.serialization.InputExtensions.readUuid
import fr.acinq.lightning.serialization.OutputExtensions.writeUuid
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class UUIDTestsCommon : LightningTestSuite() {
    @Test
    fun `Uuid conversions`() {
        val uuidBytes = "6ab99b1d23457ec53fc4817f93fd8548".hexToByteArray()
        val uuidString = "6ab99b1d-2345-7ec5-3fc4-817f93fd8548"

        // FAIL
        // Expected :6ab99b1d-2345-7ec5-3fc4-817f93fd8548
        // Actual   :6ab99b1d-2345-4ec5-bfc4-817f93fd8548
        fr.acinq.lightning.utils.UUID.fromBytes(uuidBytes)
            .let { assertEquals(uuidString, it.toString()) }
    }

    @Test
    fun `non-reg Uuid serialization`() {

        fun toBytes(uuid: UUID): ByteArray {
            val bos = ByteArrayOutput()
            bos.writeUuid(uuid)
            return bos.toByteArray()
        }

        fun fromBytes(bytes: ByteArray): UUID {
            val bis = ByteArrayInput(bytes)
            return bis.readUuid()
        }

        // PASS
        for(i in 0..1_000_000) {
            val uuid = UUID.randomUUID()
            assertEquals(uuid, fromBytes(toBytes(uuid)), "iteration $i")
        }

        // FAIL
        val uuid = UUID.fromString("6ab99b1d-2345-7ec5-3fc4-817f93fd8548")
        assertEquals(uuid, fromBytes(toBytes(uuid)))
    }

}