package fr.acinq.eklair

import fr.acinq.bitcoin.io.ByteArrayInput
import fr.acinq.bitcoin.io.ByteArrayOutput
import fr.acinq.bitcoin.io.Output
import fr.acinq.eklair.wire.*
import fr.acinq.secp256k1.Hex
import org.kodein.log.Logger
import org.kodein.log.LoggerFactory

@ExperimentalStdlibApi
@OptIn(ExperimentalUnsignedTypes::class)
object Wire {
    val logger = LoggerFactory.default.newLogger(Logger.Tag(Wire::class))

    fun decode(input: ByteArray): LightningMessage? {
        val stream = ByteArrayInput(input)
        val code = LightningSerializer.u16(stream)
        return when (code.toLong()) {
            Init.tag -> Init.read(stream)
            Error.tag -> Error.read(stream)
            Ping.tag -> Ping.read(stream)
            Pong.tag -> Pong.read(stream)
            OpenChannel.tag -> OpenChannel.read(stream)
            AcceptChannel.tag -> AcceptChannel.read(stream)
            FundingCreated.tag -> FundingCreated.read(stream)
            FundingSigned.tag -> FundingSigned.read(stream)
            FundingLocked.tag -> FundingLocked.read(stream)
            CommitSig.tag -> CommitSig.read(stream)
            RevokeAndAck.tag -> RevokeAndAck.read(stream)
            UpdateAddHtlc.tag -> UpdateAddHtlc.read(stream)
            else -> {
                logger.warning { "cannot decode ${Hex.encode(input)}" }
                null
            }
        }
    }

    fun encode(input: LightningMessage, out: Output) {
        when (input) {
            is LightningSerializable<*> -> {
                LightningSerializer.writeU16(input.tag.toInt(), out)
                @Suppress("UNCHECKED_CAST")
                (LightningSerializer.writeBytes(
                    (input.serializer() as LightningSerializer<LightningSerializable<*>>).write(
                        input
                    ), out
                ))
            }
            else -> {
                logger.warning { "cannot encode $input" }
                Unit
            }
        }
    }

    fun encode(input: LightningMessage): ByteArray? {
        val out = ByteArrayOutput()
        encode(input, out)
        return out.toByteArray()
    }
}