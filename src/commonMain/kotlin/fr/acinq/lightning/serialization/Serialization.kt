package fr.acinq.lightning.serialization

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.lightning.channel.ChannelContext
import fr.acinq.lightning.utils.runTrying
import fr.acinq.lightning.wire.EncryptedChannelData

object Serialization {

    fun serialize(ctx: ChannelContext, state: fr.acinq.lightning.channel.ChannelStateWithCommitments): ByteArray {
        return fr.acinq.lightning.serialization.v3.Serialization.serialize(ctx, state)
    }

    fun deserialize(bin: ByteArray): fr.acinq.lightning.channel.ChannelStateWithCommitments {
        return runTrying {
            fr.acinq.lightning.serialization.v3.Serialization.deserialize(bin)
        }.recoverWith {
            runTrying { fr.acinq.lightning.serialization.v2.Serialization.deserialize(bin) }
        }.get()
    }

    fun encrypt(key: ByteVector32, ctx: ChannelContext, state: fr.acinq.lightning.channel.ChannelStateWithCommitments): EncryptedChannelData {
        return fr.acinq.lightning.serialization.v3.Serialization.encrypt(key, ctx, state)
    }

    fun encrypt(key: PrivateKey, ctx: ChannelContext, state: fr.acinq.lightning.channel.ChannelStateWithCommitments): EncryptedChannelData = encrypt(key.value, ctx, state)

    fun decrypt(key: ByteVector32, data: ByteArray): fr.acinq.lightning.channel.ChannelStateWithCommitments {
        return runTrying {
            fr.acinq.lightning.serialization.v3.Serialization.decrypt(key, data)
        }.recoverWith {
            runTrying { fr.acinq.lightning.serialization.v2.Serialization.decrypt(key, data) }
        }.get()
    }

    fun decrypt(key: PrivateKey, data: ByteArray): fr.acinq.lightning.channel.ChannelStateWithCommitments = decrypt(key.value, data)

    fun decrypt(key: ByteVector32, backup: EncryptedChannelData): fr.acinq.lightning.channel.ChannelStateWithCommitments = decrypt(key, backup.data.toByteArray())
}