package fr.acinq.lightning.channel

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.WatchEvent
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.fsm.PersistedChannelState
import fr.acinq.lightning.crypto.KeyManager
import fr.acinq.lightning.wire.Init
import fr.acinq.lightning.wire.LightningMessage

/** Channel Events (inputs to be fed to the state machine). */
sealed class ChannelCommand {
    // @formatter:off
    data class InitInitiator(
        val fundingAmount: Satoshi,
        val pushAmount: MilliSatoshi,
        val wallet: WalletState,
        val commitTxFeerate: FeeratePerKw,
        val fundingTxFeerate: FeeratePerKw,
        val localParams: LocalParams,
        val remoteInit: Init,
        val channelFlags: Byte,
        val channelConfig: ChannelConfig,
        val channelType: ChannelType.SupportedChannelType,
        val channelOrigin: Origin? = null
    ) : ChannelCommand() {
        fun temporaryChannelId(keyManager: KeyManager): ByteVector32 = keyManager.channelKeys(localParams.fundingKeyPath).temporaryChannelId
    }

    data class InitNonInitiator(
        val temporaryChannelId: ByteVector32,
        val fundingAmount: Satoshi,
        val pushAmount: MilliSatoshi,
        val wallet: WalletState,
        val localParams: LocalParams,
        val channelConfig: ChannelConfig,
        val remoteInit: Init
    ) : ChannelCommand()

    data class Restore(val state: PersistedChannelState) : ChannelCommand()
    object CheckHtlcTimeout : ChannelCommand()
    data class MessageReceived(val message: LightningMessage) : ChannelCommand()
    data class WatchReceived(val watch: WatchEvent) : ChannelCommand()
    data class ExecuteCommand(val command: Command) : ChannelCommand()
    data class GetHtlcInfosResponse(val revokedCommitTxId: ByteVector32, val htlcInfos: List<ChannelAction.Storage.HtlcInfo>) : ChannelCommand()
    object Disconnected : ChannelCommand()
    data class Connected(val localInit: Init, val remoteInit: Init) : ChannelCommand()
    // @formatter:on
}