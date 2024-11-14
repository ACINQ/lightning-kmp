package fr.acinq.lightning.db

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.db.sqlite.*
import fr.acinq.lightning.db.sqlite.adapters.JsonColumnAdapter
import fr.acinq.lightning.db.sqlite.converters.InboundLiquidityOutgoingPaymentConverter
import fr.acinq.lightning.db.sqlite.converters.LightningIncomingPaymentConverter
import fr.acinq.lightning.utils.UUID.Companion.randomUUID
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.wire.LiquidityAds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PaymentsDbTestsCommon {

    @Test
    fun `lightning incoming`() = runTest {
        val driver = createSqlDriver()
        val queries = InLightningQueries(driver, In_lightning.Adapter(JsonColumnAdapter(LightningIncomingPaymentConverter)))
    }

    @Test
    fun `sqldelight test`() = runTest {
        val driver = createSqlDriver()
        val queries = OutLiquidityQueries(driver, Out_liquidity.Adapter(JsonColumnAdapter(InboundLiquidityOutgoingPaymentConverter)))
        val o = InboundLiquidityOutgoingPayment(
            id = randomUUID(),
            localMiningFees = 42.sat,
            channelId = randomBytes32(),
            txId = TxId(randomBytes32()),
            purchase = LiquidityAds.Purchase.Standard(456.sat, LiquidityAds.Fees(400.sat, 56.sat), LiquidityAds.PaymentDetails.FromChannelBalance),
            createdAt = 123456,
            confirmedAt = null,
            lockedAt = null
        )
        queries.insert(
            o.id.toString(),
            o.channelId.toByteArray(),
            o.txId.value.toByteArray(),
            o.localMiningFees.sat,
            o.purchase::class.simpleName.toString(),
            o.createdAt,
            o.confirmedAt,
            o.lockedAt,
            o
        )
        println(queries.get(o.id.toString()).executeAsOne())
        println(queries.getByTxId(o.txId.value.toByteArray()).executeAsOne())
    }

}