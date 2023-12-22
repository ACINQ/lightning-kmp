package fr.acinq.lightning.blockchain.electrum

import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.Lightning
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.channel.FundingContributions.Companion.stripInputWitnesses
import fr.acinq.lightning.channel.LocalFundingStatus
import fr.acinq.lightning.channel.RbfStatus
import fr.acinq.lightning.channel.SignedSharedTransaction
import fr.acinq.lightning.channel.SpliceStatus
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.io.RequestChannelOpen
import fr.acinq.lightning.utils.MDCLogger
import fr.acinq.lightning.utils.sat

internal sealed class SwapInCommand {
    data class TrySwapIn(val currentBlockHeight: Int, val wallet: WalletState, val swapInParams: SwapInParams, val trustedTxs: Set<TxId>) : SwapInCommand()
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
            val availableWallet = cmd.wallet.withoutReservedUtxos(reservedUtxos).withConfirmations(cmd.currentBlockHeight, cmd.swapInParams)
            logger.info { "swap-in wallet balance: deeplyConfirmed=${availableWallet.deeplyConfirmed.balance}, weaklyConfirmed=${availableWallet.weaklyConfirmed.balance}, unconfirmed=${availableWallet.unconfirmed.balance}" }
            val utxos = buildSet {
                // some utxos may be used for swap-in even if they are not confirmed, for example when migrating from the legacy phoenix android app
                addAll(availableWallet.all.filter { cmd.trustedTxs.contains(it.outPoint.txid) })
                addAll(availableWallet.deeplyConfirmed.filter { Transaction.write(it.previousTx.stripInputWitnesses()).size < 65_000 })
            }.toList()
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
         * Return the list of wallet inputs already used in channel funding attempts.
         * These inputs should not be reused in other funding attempts, otherwise we would double-spend ourselves.
         */
        fun reservedWalletInputs(channels: List<PersistedChannelState>): Set<OutPoint> {
            return buildSet {
                for (channel in channels) {
                    // Add all unsigned inputs currently used to build a funding tx that isn't broadcast yet (creation, rbf, splice).
                    when {
                        channel is WaitForFundingSigned -> addAll(channel.signingSession.fundingTx.tx.localInputs.map { it.outPoint })
                        channel is WaitForFundingConfirmed && channel.rbfStatus is RbfStatus.WaitingForSigs -> addAll(channel.rbfStatus.session.fundingTx.tx.localInputs.map { it.outPoint })
                        channel is Normal && channel.spliceStatus is SpliceStatus.WaitingForSigs -> addAll(channel.spliceStatus.session.fundingTx.tx.localInputs.map { it.outPoint })
                        else -> {}
                    }
                    // Add all inputs from previously broadcast funding txs.
                    // We include confirmed transactions as well, in case our electrum server (and thus our wallet) isn't up-to-date.
                    when (channel) {
                        is ChannelStateWithCommitments -> channel.commitments.all
                            .map { it.localFundingStatus }
                            .forEach { fundingStatus ->
                                when (fundingStatus) {
                                    is LocalFundingStatus.UnconfirmedFundingTx -> addAll(fundingStatus.sharedTx.tx.localInputs.map { it.outPoint })
                                    is LocalFundingStatus.ConfirmedFundingTx -> addAll(fundingStatus.signedTx.txIn.map { it.outPoint })
                                }
                            }
                        else -> {}
                    }
                }
            }
        }
    }
}