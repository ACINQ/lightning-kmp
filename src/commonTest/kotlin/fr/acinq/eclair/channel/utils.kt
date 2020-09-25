package fr.acinq.eclair.channel

import fr.acinq.eclair.wire.CommitSig
import fr.acinq.eclair.wire.LightningMessage
import fr.acinq.eclair.wire.RevokeAndAck
import fr.acinq.eclair.wire.UpdateAddHtlc
import kotlin.test.fail

data class NodePair(val sender: ChannelState, val receiver: ChannelState)

// LN Message
internal inline fun <reified T> List<ChannelAction>.hasMessage() = any { it is SendMessage && it.message is T }
internal fun List<ChannelAction>.messages() = filterIsInstance<SendMessage>().map { it.message }
internal inline fun <reified T> List<LightningMessage>.msg() = firstOrNull { it is T } as T ?: fail("A LN message of type ${T::class} is missing.")
// Commands
internal inline fun <reified T> List<ChannelAction>.hasCommand() = any { it is ProcessCommand && it.command is T }
internal fun List<ChannelAction>.commands() = filterIsInstance<ProcessCommand>().map { it.command }
internal inline fun <reified T> List<Command>.cmd() = firstOrNull { it is T } as T ?: fail("A Command of type ${T::class} is missing.")
