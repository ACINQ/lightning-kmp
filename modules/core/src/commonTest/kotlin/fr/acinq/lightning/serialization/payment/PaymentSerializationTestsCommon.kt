package fr.acinq.lightning.serialization.payment

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto.sha256
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.byteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.Lightning.randomKey
import fr.acinq.lightning.db.*
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.payment.OfferPaymentMetadata
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.LiquidityAds
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentSerializationTestsCommon {

    @Test
    fun `payment serialization round trip`() {
        val payments = listOf(
            randomBytes32().let { preimage ->
                Bolt11IncomingPayment(
                    preimage = preimage,
                    paymentRequest = Bolt11Invoice.create(
                        Chain.Testnet4,
                        132_000_000.msat,
                        sha256(preimage).byteVector32(),
                        randomKey(),
                        Either.Left("test payment"),
                        CltvExpiryDelta(400),
                        Features(
                            Feature.VariableLengthOnion to FeatureSupport.Mandatory,
                            Feature.PaymentSecret to FeatureSupport.Mandatory,
                            Feature.BasicMultiPartPayment to FeatureSupport.Optional,
                            Feature.ExperimentalTrampolinePayment to FeatureSupport.Optional
                        )
                    ),
                    parts = emptyList(),
                    liquidityPurchaseDetails = null,
                    createdAt = 1_234_567_890_000
                )
            },
            randomBytes32().let { preimage ->
                Bolt11IncomingPayment(
                    preimage = preimage,
                    paymentRequest = Bolt11Invoice.create(
                        Chain.Testnet4,
                        200_000_000.msat,
                        sha256(preimage).byteVector32(),
                        randomKey(),
                        Either.Left("test payment"),
                        CltvExpiryDelta(400),
                        Features(
                            Feature.VariableLengthOnion to FeatureSupport.Mandatory,
                            Feature.PaymentSecret to FeatureSupport.Mandatory,
                            Feature.BasicMultiPartPayment to FeatureSupport.Optional,
                            Feature.ExperimentalTrampolinePayment to FeatureSupport.Optional
                        )
                    ),
                    parts = listOf(
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, null, 1_444_5555_666_000),
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, LiquidityAds.FundingFee(100_000_000.msat, TxId(randomBytes32())), 1_444_5555_666_001),
                        LightningIncomingPayment.Part.FeeCredit(1_000.msat, 1_444_5555_666_002),
                    ),
                    liquidityPurchaseDetails = LiquidityAds.LiquidityTransactionDetails(
                        txId = TxId(randomBytes32()),
                        miningFee = 550.sat,
                        purchase = LiquidityAds.Purchase.WithFeeCredit(150_000.sat, LiquidityAds.Fees(500.sat, 100.sat), 1_000.msat, LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(sha256(preimage).byteVector32()))),
                    ),
                    createdAt = 1_234_567_890_000
                )
            },
            randomBytes32().let { preimage ->
                Bolt12IncomingPayment(
                    preimage = preimage,
                    metadata = OfferPaymentMetadata.V1(randomBytes32(), 35_000_000.msat, preimage, randomKey().publicKey(), "my offer", quantity = 1, createdAtMillis = 1_111_111_111_000),
                    parts = emptyList(),
                    liquidityPurchaseDetails = null,
                    createdAt = 1_234_567_890_000
                )
            },
            randomBytes32().let { preimage ->
                Bolt12IncomingPayment(
                    preimage = preimage,
                    metadata = OfferPaymentMetadata.V1(randomBytes32(), 35_000_000.msat, preimage, randomKey().publicKey(), "my offer", quantity = 1, createdAtMillis = 1_111_111_111_000),
                    parts = listOf(
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, null, 1_444_5555_666_000),
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, LiquidityAds.FundingFee(100_000_000.msat, TxId(randomBytes32())), 1_444_5555_666_001),
                        LightningIncomingPayment.Part.FeeCredit(1_000.msat, 1_444_5555_666_002),
                    ),
                    liquidityPurchaseDetails = LiquidityAds.LiquidityTransactionDetails(
                        txId = TxId(randomBytes32()),
                        miningFee = 550.sat,
                        purchase = LiquidityAds.Purchase.WithFeeCredit(150_000.sat, LiquidityAds.Fees(500.sat, 100.sat), 1_000.msat, LiquidityAds.PaymentDetails.FromFutureHtlcWithPreimage(listOf(preimage))),
                    ),
                    createdAt = 1_234_567_890_000
                )
            },
            NewChannelIncomingPayment(
                id = UUID.randomUUID(),
                amountReceived = 100_000_000.msat,
                miningFee = 5_000.sat,
                serviceFee = 100_000.msat,
                liquidityPurchase = null,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                localInputs = setOf(OutPoint(TxId(randomBytes32()), 1)),
                createdAt = 1_111_111_111_000,
                confirmedAt = null,
                lockedAt = 1_222_222_222_000,
            ),
            NewChannelIncomingPayment(
                id = UUID.randomUUID(),
                amountReceived = 100_000_000.msat,
                miningFee = 5_000.sat,
                serviceFee = 100_000.msat,
                liquidityPurchase = LiquidityAds.Purchase.Standard(250_000.sat, LiquidityAds.Fees(2_500.sat, 100.sat), LiquidityAds.PaymentDetails.FromChannelBalance),
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                localInputs = setOf(OutPoint(TxId(randomBytes32()), 1)),
                createdAt = 1_111_111_111_000,
                confirmedAt = null,
                lockedAt = 1_222_222_222_000,
            ),
            NewChannelIncomingPayment(
                id = UUID.randomUUID(),
                amountReceived = 100_000_000.msat,
                miningFee = 5_000.sat,
                serviceFee = 100_000.msat,
                liquidityPurchase = LiquidityAds.Purchase.WithFeeCredit(250_000.sat, LiquidityAds.Fees(2_500.sat, 100.sat), 2_000_000.msat, LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(randomBytes32()))),
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                localInputs = setOf(OutPoint(TxId(randomBytes32()), 1)),
                createdAt = 1_111_111_111_000,
                confirmedAt = null,
                lockedAt = 1_222_222_222_000,
            ),
            SpliceInIncomingPayment(
                id = UUID.randomUUID(),
                amountReceived = 50_000_000.msat,
                miningFee = 5_000.sat,
                liquidityPurchase = null,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                localInputs = setOf(OutPoint(TxId(randomBytes32()), 1), OutPoint(TxId(randomBytes32()), 5)),
                createdAt = 1_111_111_111_000,
                confirmedAt = null,
                lockedAt = 1_222_222_222_000,
            ),
            SpliceInIncomingPayment(
                id = UUID.randomUUID(),
                amountReceived = 50_000_000.msat,
                miningFee = 5_000.sat,
                liquidityPurchase = LiquidityAds.Purchase.Standard(100_000.sat, LiquidityAds.Fees(2_500.sat, 100.sat), LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(randomBytes32()))),
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                localInputs = setOf(OutPoint(TxId(randomBytes32()), 1), OutPoint(TxId(randomBytes32()), 5)),
                createdAt = 1_111_111_111_000,
                confirmedAt = null,
                lockedAt = 1_222_222_222_000,
            ),
            randomBytes32().let { preimage ->
                @Suppress("DEPRECATION")
                LegacyPayToOpenIncomingPayment(
                    paymentPreimage = preimage,
                    origin = LegacyPayToOpenIncomingPayment.Origin.Invoice(
                        paymentRequest = Bolt11Invoice.create(
                            Chain.Testnet4,
                            132_000_000.msat,
                            sha256(preimage).byteVector32(),
                            randomKey(),
                            Either.Left("test payment"),
                            CltvExpiryDelta(400),
                            Features(
                                Feature.VariableLengthOnion to FeatureSupport.Mandatory,
                                Feature.PaymentSecret to FeatureSupport.Mandatory,
                                Feature.BasicMultiPartPayment to FeatureSupport.Optional,
                                Feature.ExperimentalTrampolinePayment to FeatureSupport.Optional
                            )
                        )
                    ),
                    parts = emptyList(),
                    createdAt = 1_234_567_890_000,
                    completedAt = null
                )
            },
            SpliceOutgoingPayment(
                id = UUID.randomUUID(),
                recipientAmount = 50_000.sat,
                address = "bcrt1q9qt02fkc2rfpm3w37uvec62kd7yh688uyf8v4w",
                miningFee = 250.sat,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                liquidityPurchase = null,
                createdAt = 1729,
                confirmedAt = null,
                lockedAt = null,
            ),
            SpliceOutgoingPayment(
                id = UUID.randomUUID(),
                recipientAmount = 50_000.sat,
                address = "bcrt1q9qt02fkc2rfpm3w37uvec62kd7yh688uyf8v4w",
                miningFee = 250.sat,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                liquidityPurchase = LiquidityAds.Purchase.Standard(
                    amount = 100_000.sat,
                    fees = LiquidityAds.Fees(miningFee = 150.sat, serviceFee = 550.sat),
                    paymentDetails = LiquidityAds.PaymentDetails.FromChannelBalance,
                ),
                createdAt = 1729,
                confirmedAt = 2538,
                lockedAt = 1987,
            ),
            SpliceCpfpOutgoingPayment(
                id = UUID.randomUUID(),
                miningFee = 1151.sat,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                createdAt = 1729,
                confirmedAt = null,
                lockedAt = 1932,
            ),
            ManualLiquidityPurchasePayment(
                id = UUID.randomUUID(),
                miningFee = 1151.sat,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                liquidityPurchase = LiquidityAds.Purchase.Standard(
                    amount = 100_000.sat,
                    fees = LiquidityAds.Fees(miningFee = 150.sat, serviceFee = 550.sat),
                    paymentDetails = LiquidityAds.PaymentDetails.FromChannelBalance,
                ),
                createdAt = 1729,
                confirmedAt = null,
                lockedAt = null,
            ),
            AutomaticLiquidityPurchasePayment(
                id = UUID.randomUUID(),
                miningFee = 1151.sat,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                liquidityPurchase = LiquidityAds.Purchase.Standard(
                    amount = 100_000.sat,
                    fees = LiquidityAds.Fees(miningFee = 150.sat, serviceFee = 550.sat),
                    paymentDetails = LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(listOf(randomBytes32())),
                ),
                createdAt = 1729,
                confirmedAt = null,
                lockedAt = null,
                incomingPaymentReceivedAt = null
            ),
            AutomaticLiquidityPurchasePayment(
                id = UUID.randomUUID(),
                miningFee = 1151.sat,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                liquidityPurchase = LiquidityAds.Purchase.Standard(
                    amount = 100_000.sat,
                    fees = LiquidityAds.Fees(miningFee = 150.sat, serviceFee = 550.sat),
                    paymentDetails = LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(randomBytes32())),
                ),
                createdAt = 1729,
                confirmedAt = 1731,
                lockedAt = 1731,
                incomingPaymentReceivedAt = 1735
            ),
            ChannelCloseOutgoingPayment(
                id = UUID.randomUUID(),
                recipientAmount = 50_000.sat,
                address = "bcrt1q9qt02fkc2rfpm3w37uvec62kd7yh688uyf8v4w",
                isSentToDefaultAddress = false,
                miningFee = 253.sat,
                channelId = randomBytes32(),
                txId = TxId(randomBytes32()),
                createdAt = 1729,
                confirmedAt = 1730,
                lockedAt = 1731,
                closingType = ChannelCloseOutgoingPayment.ChannelClosingType.Mutual
            )
        )

        payments.forEach {
            assertEquals(it, Serialization.deserialize(Serialization.serialize(it)).getOrThrow())
        }
    }
}