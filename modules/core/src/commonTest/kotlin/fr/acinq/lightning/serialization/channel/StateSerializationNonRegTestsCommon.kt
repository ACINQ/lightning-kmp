package fr.acinq.lightning.serialization.channel

import fr.acinq.lightning.json.JsonSerializers
import fr.acinq.lightning.tests.utils.TestHelpers
import fr.acinq.lightning.utils.value
import fr.acinq.secp256k1.Hex
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

import kotlin.test.Test
import kotlin.test.assertEquals

class StateSerializationNonRegTestsCommon {

    /**
     * If test doesn't pass, set debug to `true`, run the test again and look for the `actual.json` file next to `data.json` in the resources. Then just
     * compare the two json files (easy within IntelliJ) and see what the difference is.
     */
    fun regtest(dir: String, debug: Boolean) {
        val root = Path(TestHelpers.resourcesPath, "nonreg", dir)
        SystemFileSystem.list(root).forEach {
            val bin = SystemFileSystem.source(Path(it, "data.bin")).buffered().readString()
            val ref = SystemFileSystem.source(Path(it, "data.json")).buffered().readString()
            val state = Serialization.deserialize(Hex.decode(bin)).value
            val json = JsonSerializers.json.encodeToString(state)
            val tmpFile = Path(it, "actual.json")
            if (debug) {
                SystemFileSystem.sink(tmpFile).buffered().use { sink -> sink.writeString(json) }
            }
            // deserialized data must match static json reference file
            assertEquals(ref, json, it.toString())
            if (debug) {
                SystemFileSystem.delete(tmpFile)
            }
            // we also make sure that serialization round-trip is identity
            assertEquals(state, Serialization.deserialize(Serialization.serialize(state)).value, it.toString())
        }
    }

    @Test
    fun `non-reg test with v2 serialization`() {
        regtest("v2", debug = false)
    }

    @Test
    fun `non-reg test with v3 serialization`() {
        regtest("v3", debug = false)
    }

    @Test
    fun `non-reg test with v4 serialization`() {
        regtest("v4", debug = false)
    }
}