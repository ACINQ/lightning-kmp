package fr.acinq.lightning.blockchain

import fr.acinq.bitcoin.Transaction
import kotlinx.coroutines.flow.Flow

interface IWatcher {
    fun openWatchNotificationsFlow(): Flow<WatchTriggered>
    suspend fun watch(watch: Watch)
    suspend fun publish(tx: Transaction)
}