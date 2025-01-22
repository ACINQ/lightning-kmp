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
                    createdAt = 1_234_567_890_000
                )
            },
            randomBytes32().let { preimage ->
                val paymentHash = sha256(preimage).byteVector32()
                Bolt11IncomingPayment(
                    preimage = preimage,
                    paymentRequest = Bolt11Invoice.create(
                        Chain.Testnet4,
                        200_000_000.msat,
                        paymentHash,
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
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, null, null, 1_444_5555_666_000),
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, LiquidityAds.Purchase.Standard(500_000.sat, LiquidityAds.Fees(10_000.sat, 990_000.sat), LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(paymentHash))), LiquidityAds.FundingFee(100_000_000.msat, TxId(randomBytes32())), 1_444_5555_666_001),
                        LightningIncomingPayment.Part.FeeCredit(1_000.msat, 1_444_5555_666_002),
                    ),
                    createdAt = 1_234_567_890_000
                )
            },
            randomBytes32().let { preimage ->
                Bolt12IncomingPayment(
                    preimage = preimage,
                    metadata = OfferPaymentMetadata.V1(randomBytes32(), 35_000_000.msat, preimage, randomKey().publicKey(), "my offer", quantity = 1, createdAtMillis = 1_111_111_111_000),
                    parts = emptyList(),
                    createdAt = 1_234_567_890_000
                )
            },
            randomBytes32().let { preimage ->
                val paymentHash = sha256(preimage).byteVector32()
                Bolt12IncomingPayment(
                    preimage = preimage,
                    metadata = OfferPaymentMetadata.V1(randomBytes32(), 35_000_000.msat, preimage, randomKey().publicKey(), "my offer", quantity = 1, createdAtMillis = 1_111_111_111_000),
                    parts = listOf(
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, null, null, 1_444_5555_666_000),
                        LightningIncomingPayment.Part.Htlc(200_000_111.msat, randomBytes32(), 42, LiquidityAds.Purchase.Standard(500_000.sat, LiquidityAds.Fees(10_000.sat, 990_000.sat), LiquidityAds.PaymentDetails.FromFutureHtlc(listOf(paymentHash))), LiquidityAds.FundingFee(100_000_000.msat, TxId(randomBytes32())), 1_444_5555_666_001),
                        LightningIncomingPayment.Part.FeeCredit(1_000.msat, 1_444_5555_666_002),
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
            SpliceInIncomingPayment(
                id = UUID.randomUUID(),
                amountReceived = 50_000_000.msat,
                miningFee = 5_000.sat,
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
            }
        )

        payments.forEach {
            assertEquals(it, Serialization.deserialize(Serialization.serialize(it)).getOrThrow())
        }
    }
}