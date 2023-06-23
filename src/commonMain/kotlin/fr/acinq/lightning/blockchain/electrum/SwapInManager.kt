package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.OutPoint
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.channel.RbfStatus
import fr.acinq.lightning.channel.SignedSharedTransaction
import fr.acinq.lightning.channel.SpliceStatus
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.io.RequestChannelOpen
import fr.acinq.lightning.utils.MDCLogger
import fr.acinq.lightning.utils.sat

internal sealed class SwapInCommand {
    data class TrySwapIn(val currentBlockHeight: Int, val wallet: WalletState, val swapInConfirmations: Int, val isMigrationFromLegacyApp: Boolean) : SwapInCommand()
    data class UnlockWalletInputs(val inputs: Set<OutPoint>) : SwapInCommand()
}

/**
 * This object selects inputs that are ready to be used for swaps and keeps track of those that are currently used in channel funding attempts.
 * Those inputs should not be reused, otherwise we would double-spend ourselves.
 * If the electrum server we connect to has our channel funding attempts in their mempool, those inputs wouldn't be added to our wallet at all.
 * But we cannot rely only on that, since we may connect to a different electrum server after a restart, or transactions may be evicted from their mempool.
 * Since we don't have an easy way of asking electrum to check for double-spends, we would end up with channels that are stuck waiting for confirmations.
 * This generally wouldn't be a security issue (only one of the funding attempts would succeed), unless 0-conf is used and our LSP is malicious.
 *
 * Note: this object is *not* thread-safe and should be used in a dedicated coroutine.
 */
class SwapInManager(private var reservedUtxos: Set<OutPoint>, private val logger: MDCLogger) {
    constructor(bootChannels: List<PersistedChannelState>, logger: MDCLogger) : this(reservedWalletInputs(bootChannels), logger)

    internal fun process(cmd: SwapInCommand): RequestChannelOpen? = when (cmd) {
        is SwapInCommand.TrySwapIn -> {
            val availableWallet = cmd.wallet.withoutReservedUtxos(reservedUtxos).withConfirmations(cmd.currentBlockHeight, cmd.swapInConfirmations)
            logger.info { "swap-in wallet balance (migration=${cmd.isMigrationFromLegacyApp}): deeplyConfirmed=${availableWallet.deeplyConfirmed.balance}, weaklyConfirmed=${availableWallet.weaklyConfirmed.balance}, unconfirmed=${availableWallet.unconfirmed.balance}" }
            val utxos = when {
                // When migrating from the legacy android app, we use all utxos, even unconfirmed ones.
                cmd.isMigrationFromLegacyApp -> availableWallet.all
                else -> availableWallet.deeplyConfirmed
            }
            if (utxos.balance > 0.sat) {
                logger.info { "swap-in wallet: requesting channel using ${utxos.size} utxos with balance=${utxos.balance}" }
                reservedUtxos = reservedUtxos.union(utxos.map { it.outPoint })
                RequestChannelOpen(Lightning.randomBytes32(), utxos)
            } else {
                null
            }
        }
        is SwapInCommand.UnlockWalletInputs -> {
            logger.debug { "releasing ${cmd.inputs.size} utxos" }
            reservedUtxos = reservedUtxos - cmd.inputs
            null
        }
    }

    companion object {
        /**
         * Return the list of wallet inputs used in pending unconfirmed channel funding attempts.
         * These inputs should not be reused in other funding attempts, otherwise we would double-spend ourselves.
         */
        fun reservedWalletInputs(channels: List<PersistedChannelState>): Set<OutPoint> {
            val unconfirmedFundingTxs: List<SignedSharedTransaction> = buildList {
                for (channel in channels) {
                    // Add all unsigned inputs currently used to build a funding tx that isn't broadcast yet (creation, rbf, splice).
                    when {
                        channel is WaitForFundingSigned -> add(channel.signingSession.fundingTx)
                        channel is WaitForFundingConfirmed && channel.rbfStatus is RbfStatus.WaitingForSigs -> add(channel.rbfStatus.session.fundingTx)
                        channel is Normal && channel.spliceStatus is SpliceStatus.WaitingForSigs -> add(channel.spliceStatus.session.fundingTx)
                        else -> {}
                    }
                    // Add all inputs in unconfirmed funding txs (utxos spent by confirmed transactions will never appear in our wallet).
                    when (channel) {
                        is ChannelStateWithCommitments -> channel.commitments.all
                            .map { it.localFundingStatus }
                            .filterIsInstance<LocalFundingStatus.UnconfirmedFundingTx>()
                            .forEach { add(it.sharedTx) }
                        else -> {}
                    }
                }
            }
            val localInputs = unconfirmedFundingTxs.flatMap { fundingTx -> fundingTx.tx.localInputs.map { it.outPoint } }
            return localInputs.toSet()
        }
    }
}