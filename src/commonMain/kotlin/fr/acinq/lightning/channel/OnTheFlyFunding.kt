package fr.acinq.lightning.channel

import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.io.Input
import fr.acinq.bitcoin.io.Output
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.LightningCodecs

/**
 * Created by t-bast on 31/10/2023.
 */

object OnTheFlyFunding {
    /**
     * @param feeBase constant fee that is paid for each funding transaction.
     * @param feeProportional proportional fee (in basis points) that is paid for each funding transaction.
     */
    data class FundingRates(val feeBase: Satoshi, val feeProportional: Int) {
        fun fundingFees(fundingAmount: Satoshi): Satoshi = feeBase + fundingAmount * feeProportional / 10_000

        fun write(out: Output) {
            LightningCodecs.writeU32(feeBase.toLong().toInt(), out)
            LightningCodecs.writeU16(feeProportional, out)
        }

        companion object {
            fun read(input: Input): FundingRates {
                return FundingRates(
                    feeBase = LightningCodecs.u32(input).sat,
                    feeProportional = LightningCodecs.u16(input),
                )
            }
        }
    }
}