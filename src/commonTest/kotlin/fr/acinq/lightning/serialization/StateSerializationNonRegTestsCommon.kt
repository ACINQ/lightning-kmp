package fr.acinq.lightning.serialization

import fr.acinq.lightning.json.JsonSerializers
import fr.acinq.secp256k1.Hex
import org.kodein.memory.file.*
import org.kodein.memory.system.Environment
import org.kodein.memory.text.putString
import org.kodein.memory.text.readString
import kotlin.test.Test
import kotlin.test.assertEquals

class StateSerializationNonRegTestsCommon {

    /**
     * If test doesn't pass, set debug to `true`, run the test again and look for the `actual.json` file next to `data.json` in the resources. Then just
     * compare the two json files (easy within IntelliJ) and see what the difference is.
     */
    fun regtest(dir: String, debug: Boolean) {
        val resourceDir = Environment.findVariable("TEST_RESOURCES_PATH")?.let { Path(it) } ?: FileSystem.workingDir().resolve("src/commonTest/resources")
        val file = resourceDir.resolve("bolt3_anchor_outputs_test_vectors.json")
        file.openReadableFile().run { readString(sizeBytes = remaining) }

        Path(Environment.findVariable("TEST_RESOURCES_PATH")!!)
            .resolve("nonreg", dir)
            .listDir() // list all test cases
            .forEach { path ->
                val bin = path.resolve("data.bin").openReadableFile().run { readString(sizeBytes = remaining) }
                val ref = path.resolve("data.json").openReadableFile().run { readString(sizeBytes = remaining) }
                val state = Serialization.deserialize(Hex.decode(bin))
                val json = JsonSerializers.toJsonString(state)
                val tmpFile = path.resolve("actual.json")
                if (debug) {
                    tmpFile.openWriteableFile().run {
                        putString(json)
                        close()
                    }
                }
                assertEquals(ref, json, path.toString())
                if (debug) {
                    tmpFile.delete()
                }
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
}