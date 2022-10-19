package fr.acinq.lightning.db.sqlite

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.channel.ChannelContext
import fr.acinq.lightning.channel.ChannelStateWithCommitments
import fr.acinq.lightning.db.ChannelsDb
import fr.acinq.lightning.serialization.Serialization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Statement


class SqliteChannelsDb(val nodeParams: NodeParams, val sqlite: Connection) : ChannelsDb {
    /**
     * This helper makes sure statements are correctly closed.
     *
     * @param inTransaction if set to true, all updates in the block will be run in a transaction.
     */
    fun <T : Statement, U> using(statement: T, inTransaction: Boolean = false, block: (T) -> U): U {
        val autoCommit = statement.connection.autoCommit
        try {
            if (inTransaction) statement.connection.autoCommit = false
            val res = block(statement)
            if (inTransaction) statement.connection.commit()
            return res
        } catch (e: Exception) {
            if (inTransaction) statement.connection.rollback()
            throw e
        } finally {
            if (inTransaction) statement.connection.autoCommit = autoCommit
            statement.close()
        }
    }

    init {
        // The SQLite documentation states that "It is not possible to enable or disable foreign key constraints in the middle
        // of a multi-statement transaction (when SQLite is not in autocommit mode).".
        // So we need to set foreign keys before we initialize tables / migrations (which is done inside a transaction).
        using(sqlite.createStatement()) { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
        }

        using(sqlite.createStatement(), inTransaction = true) { statement ->
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT 0)")
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id BLOB NOT NULL, commitment_number BLOB NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
        }

    }

    override suspend fun addOrUpdateChannel(state: ChannelStateWithCommitments) {
        error("not implemented")
//        withContext(Dispatchers.IO) {
//            val data = Serialization.serialize(null as ChannelContext, state)
//            using(sqlite.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")) { update ->
//                update.setBytes(1, data)
//                update.setBytes(2, state.channelId.toByteArray())
//                if (update.executeUpdate() == 0) {
//                    using(sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?, 0)")) { statement ->
//                        statement.setBytes(1, state.channelId.toByteArray())
//                        statement.setBytes(2, data)
//                        statement.executeUpdate()
//                    }
//                }
//            }
//        }
    }

    override suspend fun removeChannel(channelId: ByteVector32) {
        withContext(Dispatchers.IO) {
            using(sqlite.prepareStatement("DELETE FROM pending_relay WHERE channel_id=?")) { statement ->
                statement.setBytes(1, channelId.toByteArray())
                statement.executeUpdate()
            }

            using(sqlite.prepareStatement("DELETE FROM htlc_infos WHERE channel_id=?")) { statement ->
                statement.setBytes(1, channelId.toByteArray())
                statement.executeUpdate()
            }

            using(sqlite.prepareStatement("UPDATE local_channels SET is_closed=1 WHERE channel_id=?")) { statement ->
                statement.setBytes(1, channelId.toByteArray())
                statement.executeUpdate()
            }
        }
    }

    override suspend fun listLocalChannels(): List<ChannelStateWithCommitments> {
        return withContext(Dispatchers.IO) {
            using(sqlite.createStatement()) { statement ->
                val rs = statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=0")
                val result = ArrayList<ChannelStateWithCommitments>()
                while (rs.next()) {
                    result.add(Serialization.deserialize(rs.getBytes("data")))
                }
                result
            }
        }
    }

    override suspend fun addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit {
        withContext(Dispatchers.IO) {
            using(sqlite.prepareStatement("INSERT INTO htlc_infos VALUES (?, ?, ?, ?)")) { statement ->
                statement.setBytes(1, channelId.toByteArray())
                statement.setLong(2, commitmentNumber)
                statement.setBytes(3, paymentHash.toByteArray())
                statement.setLong(4, cltvExpiry.toLong())
                statement.executeUpdate()
            }
        }
    }

    override suspend fun listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): List<Pair<ByteVector32, CltvExpiry>> {
        return withContext(Dispatchers.IO) {
            using(sqlite.prepareStatement("SELECT payment_hash, cltv_expiry FROM htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement ->
                statement.setBytes(1, channelId.toByteArray())
                statement.setLong(2, commitmentNumber)
                val rs = statement.executeQuery()
                val result = ArrayList<Pair<ByteVector32, CltvExpiry>>()
                while (rs.next()) {
                    result.add(Pair(ByteVector32(rs.getBytes("payment_hash")), CltvExpiry(rs.getLong("cltv_expiry"))))
                }
                result
            }
        }
    }

    // used by mobile apps
    override fun close(): Unit = sqlite.close()
}
