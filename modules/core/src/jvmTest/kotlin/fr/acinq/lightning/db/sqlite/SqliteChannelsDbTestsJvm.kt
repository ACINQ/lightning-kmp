package fr.acinq.lightning.db.sqlite

import fr.acinq.lightning.channel.TestsHelper
import fr.acinq.lightning.tests.utils.LightningTestSuite
import fr.acinq.lightning.utils.sat
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteChannelsDbTestsJvm : LightningTestSuite() {
    private fun sqliteInMemory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @Test
    fun `basic tests`() {
        runBlocking {
            val db = SqliteChannelsDb(sqliteInMemory())
            val (alice, _) = TestsHelper.reachNormal(currentHeight = 1, aliceFundingAmount = 1_000_000.sat)
            db.addOrUpdateChannel(alice.state)
            val aliceWithoutNonces = alice.state.copy(remoteNextCommitNonces = mapOf(), localCloseeNonce = null, localCloserNonces = null)
            val (bob, _) = TestsHelper.reachNormal(currentHeight = 2, aliceFundingAmount = 2_000_000.sat)
            db.addOrUpdateChannel(bob.state)
            val bobWithoutNonces = bob.state.copy(remoteNextCommitNonces = mapOf(), localCloseeNonce = null, localCloserNonces = null)
            assertEquals(db.listLocalChannels(), listOf(aliceWithoutNonces, bobWithoutNonces))
        }
    }
}