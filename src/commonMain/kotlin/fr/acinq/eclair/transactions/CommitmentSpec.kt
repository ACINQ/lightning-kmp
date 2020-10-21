package fr.acinq.eclair.transactions

import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.wire.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

sealed class CommitmentOutput {
    object ToLocal : CommitmentOutput()

    object ToRemote : CommitmentOutput()

    data class InHtlc(val incomingHtlc: IncomingHtlc) : CommitmentOutput()

    data class OutHtlc(val outgoingHtlc: OutgoingHtlc) : CommitmentOutput()
}

@Serializable
sealed class DirectedHtlc {
    abstract val add: UpdateAddHtlc

    fun opposite(): DirectedHtlc = when (this) {
        is IncomingHtlc -> OutgoingHtlc(add)
        is OutgoingHtlc -> IncomingHtlc(add)
    }

    fun direction(): String = when (this) {
        is IncomingHtlc -> "IN"
        is OutgoingHtlc -> "OUT"
    }
}

@Serializable
data class IncomingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()
@Serializable
data class OutgoingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

fun Iterable<DirectedHtlc>.incomings(): List<UpdateAddHtlc> = mapNotNull { (it as? IncomingHtlc)?.add }
fun Iterable<DirectedHtlc>.outgoings(): List<UpdateAddHtlc> = mapNotNull { (it as? OutgoingHtlc)?.add }


@Serializable
data class CommitmentSpec(
    val htlcs: Set<DirectedHtlc>,
    val feeratePerKw: Long,
    val toLocal: MilliSatoshi,
    val toRemote: MilliSatoshi
) {

    fun findIncomingHtlcById(id: Long): IncomingHtlc? =
        htlcs.find { it is IncomingHtlc && it.add.id == id } as IncomingHtlc?

    fun findOutgoingHtlcById(id: Long): OutgoingHtlc? =
        htlcs.find { it is OutgoingHtlc && it.add.id == id } as OutgoingHtlc?

    @Transient
    val totalFunds: MilliSatoshi = toLocal + toRemote + MilliSatoshi(htlcs.map { it.add.amountMsat.toLong() }.sum())

    companion object {
        fun removeHtlc(changes: List<UpdateMessage>, id: Long): List<UpdateMessage> =
            changes.filterNot { it is UpdateAddHtlc && it.id == id }

        fun addHtlc(spec: CommitmentSpec, directedHtlc: DirectedHtlc): CommitmentSpec {
            return when (directedHtlc) {
                is OutgoingHtlc -> spec.copy(
                    toLocal = spec.toLocal - directedHtlc.add.amountMsat,
                    htlcs = spec.htlcs + directedHtlc
                )
                is IncomingHtlc -> spec.copy(
                    toRemote = spec.toRemote - directedHtlc.add.amountMsat,
                    htlcs = spec.htlcs + directedHtlc
                )
            }
        }

        fun fulfillIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec {
            val htlc = spec.findIncomingHtlcById(htlcId)
            return htlc?.let { spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - it) }
                ?: throw RuntimeException("cannot find htlc id=$htlcId")
        }

        fun fulfillOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec {
            val htlc = spec.findOutgoingHtlcById(htlcId)
            return htlc?.let { spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - it) }
                ?: throw RuntimeException("cannot find htlc id=$htlcId")
        }

        fun failIncomingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec {
            val htlc = spec.findIncomingHtlcById(htlcId)
            return htlc?.let { spec.copy(toRemote = spec.toRemote + htlc.add.amountMsat, htlcs = spec.htlcs - it) }
                ?: throw RuntimeException("cannot find htlc id=$htlcId")
        }

        fun failOutgoingHtlc(spec: CommitmentSpec, htlcId: Long): CommitmentSpec {
            val htlc = spec.findOutgoingHtlcById(htlcId)
            return htlc?.let { spec.copy(toLocal = spec.toLocal + htlc.add.amountMsat, htlcs = spec.htlcs - it) }
                ?: throw RuntimeException("cannot find htlc id=$htlcId")
        }

        fun reduce(
            localCommitSpec: CommitmentSpec,
            localChanges: List<UpdateMessage>,
            remoteChanges: List<UpdateMessage>
        ): CommitmentSpec {
            val spec1 = localChanges.fold(localCommitSpec, { spec, u ->
                when (u) {
                    is UpdateAddHtlc -> addHtlc(spec, OutgoingHtlc(u))
                    else -> spec
                }
            })
            val spec2 = remoteChanges.fold(spec1, { spec, u ->
                when (u) {
                    is UpdateAddHtlc -> addHtlc(spec, IncomingHtlc(u))
                    else -> spec
                }
            })
            val spec3 = localChanges.fold(spec2, { spec, u ->
                when (u) {
                    is UpdateFulfillHtlc -> fulfillIncomingHtlc(spec, u.id)
                    is UpdateFailHtlc -> failIncomingHtlc(spec, u.id)
                    is UpdateFailMalformedHtlc -> failIncomingHtlc(spec, u.id)
                    else -> spec
                }
            })
            val spec4 = remoteChanges.fold(spec3, { spec, u ->
                when (u) {
                    is UpdateFulfillHtlc -> fulfillOutgoingHtlc(spec, u.id)
                    is UpdateFailHtlc -> failOutgoingHtlc(spec, u.id)
                    is UpdateFailMalformedHtlc -> failOutgoingHtlc(spec, u.id)
                    else -> spec
                }
            })
            val spec5 = (localChanges + remoteChanges).fold(spec4, { spec, u ->
                when (u) {
                    is UpdateFee -> spec.copy(feeratePerKw = u.feeratePerKw)
                    else -> spec
                }
            })
            return spec5
        }
    }
}
