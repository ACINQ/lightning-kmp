package fr.acinq.eklair.channel

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.Script.pay2wsh
import fr.acinq.bitcoin.Script.write
import fr.acinq.eklair.transactions.Scripts.multiSig2of2
import fr.acinq.eklair.transactions.Transactions
import kotlinx.serialization.InternalSerializationApi


@OptIn(InternalSerializationApi::class)
object Helpers {

    object Funding {

        fun makeFundingInputInfo(fundingTxId: ByteVector32, fundingTxOutputIndex: Int, fundingSatoshis: Satoshi, fundingPubkey1: PublicKey, fundingPubkey2: PublicKey): Transactions.InputInfo {
            val fundingScript = multiSig2of2(fundingPubkey1, fundingPubkey2)
            val fundingTxOut = TxOut(fundingSatoshis, pay2wsh(fundingScript))
            return Transactions.InputInfo(OutPoint(fundingTxId, fundingTxOutputIndex.toLong()), fundingTxOut, ByteVector(write(fundingScript)))
        }

    }

}