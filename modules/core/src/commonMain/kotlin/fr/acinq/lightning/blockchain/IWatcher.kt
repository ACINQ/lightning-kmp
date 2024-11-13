package fr.acinq.lightning.blockchain

import fr.acinq.bitcoin.Transaction
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import kotlinx.coroutines.flow.Flow

interface IWatcher {
    fun openWatchNotificationsFlow(): Flow<WatchEvent>

    suspend fun watch(watch: Watch)

    suspend fun publish(tx: Transaction)
}