package fr.acinq.lightning.bin.conf

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.*
import fr.acinq.lightning.payment.LiquidityPolicy
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.LiquidityAds

data class Conf(
    val chain: Chain,
    val electrumServer: ServerAddress,
    val liquidityPolicy: LiquidityPolicy = defaultLiquidityPolicy,
    val autoLiquidity: Satoshi,
) {
    data class LSP(val walletParams: WalletParams, val swapInXpub: String)

    val lsp: LSP = when(chain) {
        is Chain.Mainnet -> LSP_mainnet
        is Chain.Testnet -> LSP_testnet
        else -> error("unsupported chain $chain")
    }

    companion object {

        private val trampolineFees = listOf(
            TrampolineFees(
                feeBase = 4.sat,
                feeProportional = 4_000,
                cltvExpiryDelta = CltvExpiryDelta(576)
            )
        )

        private val invoiceDefaultRoutingFees = InvoiceDefaultRoutingFees(
            feeBase = 1_000.msat,
            feeProportional = 100,
            cltvExpiryDelta = CltvExpiryDelta(144)
        )

        private val swapInParams = SwapInParams(
            minConfirmations = DefaultSwapInParams.MinConfirmations,
            maxConfirmations = DefaultSwapInParams.MaxConfirmations,
            refundDelay = DefaultSwapInParams.RefundDelay,
        )

        val LSP_testnet = LSP(
            swapInXpub = "tpubDAmCFB21J9ExKBRPDcVxSvGs9jtcf8U1wWWbS1xTYmnUsuUHPCoFdCnEGxLE3THSWcQE48GHJnyz8XPbYUivBMbLSMBifFd3G9KmafkM9og",
            walletParams = WalletParams(
                trampolineNode = NodeUri(PublicKey.fromHex("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134"), "13.248.222.197", 9735),
                trampolineFees,
                invoiceDefaultRoutingFees,
                swapInParams
            )
        )

        val LSP_mainnet = LSP(
            swapInXpub = "xpub69q3sDXXsLuHVbmTrhqmEqYqTTsXJKahdfawXaYuUt6muf1PbZBnvqzFcwiT8Abpc13hY8BFafakwpPbVkatg9egwiMjed1cRrPM19b2Ma7",
            walletParams = WalletParams(
                trampolineNode = NodeUri(PublicKey.fromHex("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"), "3.33.236.230", 9735),
                trampolineFees,
                invoiceDefaultRoutingFees,
                swapInParams
            )
        )

        val defaultLiquidityPolicy = LiquidityPolicy.Auto(
            maxAbsoluteFee = 5_000.sat,
            maxRelativeFeeBasisPoints = 50_00 /* 50% */,
            skipAbsoluteFeeCheck = false,
            maxAllowedCredit = 100_000.sat
        )

        fun liquidityLeaseRate(amount: Satoshi): LiquidityAds.LeaseRate {
            // WARNING : THIS MUST BE KEPT IN SYNC WITH LSP OTHERWISE FUNDING REQUEST WILL BE REJECTED BY PHOENIX
            val fundingWeight = if (amount <= 100_000.sat) {
                271 * 2 // 2-inputs (wpkh) / 0-change
            } else if (amount <= 250_000.sat) {
                271 * 2 // 2-inputs (wpkh) / 0-change
            } else if (amount <= 500_000.sat) {
                271 * 4 // 4-inputs (wpkh) / 0-change
            } else if (amount <= 1_000_000.sat) {
                271 * 4 // 4-inputs (wpkh) / 0-change
            } else {
                271 * 6 // 6-inputs (wpkh) / 0-change
            }
            return LiquidityAds.LeaseRate(
                leaseDuration = 0,
                fundingWeight = fundingWeight,
                leaseFeeProportional = 100, // 1%
                leaseFeeBase = 0.sat,
                maxRelayFeeProportional = 100,
                maxRelayFeeBase = 1_000.msat
            )
        }
    }
}