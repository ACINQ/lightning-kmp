package fr.acinq.lightning.transactions

import fr.acinq.bitcoin.LexicographicalOrdering
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.wire.*

sealed class CommitmentOutput : Comparable<CommitmentOutput> {
    abstract val txOut: TxOut

    data class ToLocal(override val txOut: TxOut) : CommitmentOutput()
    data class ToRemote(override val txOut: TxOut) : CommitmentOutput()
    data class ToLocalAnchor(override val txOut: TxOut) : CommitmentOutput()
    data class ToRemoteAnchor(override val txOut: TxOut) : CommitmentOutput()
    data class InHtlc(val htlc: IncomingHtlc, override val txOut: TxOut, val htlcDelayedOutput: TxOut) : CommitmentOutput()
    data class OutHtlc(val htlc: OutgoingHtlc, override val txOut: TxOut, val htlcDelayedOutput: TxOut) : CommitmentOutput()

    override fun compareTo(other: CommitmentOutput): Int {
        return when {
            // Outgoing HTLCs that have the same payment_hash will have the same script. If they also have the same amount, they
            // will produce exactly the same output: in that case, we must sort them using their expiry (see Bolt 3).
            // If they also have the same expiry, it doesn't really matter how we sort them, but in order to provide a fully
            // deterministic ordering (which is useful for tests), we sort them by htlc_id, which cannot be equal.
            this is OutHtlc && other is OutHtlc && this.txOut == other.txOut && this.htlc.add.cltvExpiry == other.htlc.add.cltvExpiry -> this.htlc.add.id.compareTo(other.htlc.add.id)
            this is OutHtlc && other is OutHtlc && this.txOut == other.txOut -> this.htlc.add.cltvExpiry.compareTo(other.htlc.add.cltvExpiry)
            // Incoming HTLCs that have the same payment_hash *and* expiry will have the same script. If they also have the same
            // amount, they will produce exactly the same output: just like offered HTLCs, it doesn't really matter how we sort
            // them, but we use the htlc_id to provide a fully deterministic ordering. Note that the expiry is included in the
            // script, so HTLCs with different expiries will have different scripts, and will thus be sorted by script as required
            // by Bolt 3.
            this is InHtlc && other is InHtlc && this.txOut == other.txOut -> this.htlc.add.id.compareTo(other.htlc.add.id)
            else -> LexicographicalOrdering.compare(this.txOut, other.txOut)
        }
    }
}

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

data class IncomingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

data class OutgoingHtlc(override val add: UpdateAddHtlc) : DirectedHtlc()

fun Iterable<DirectedHtlc>.incomings(): List<UpdateAddHtlc> = mapNotNull { (it as? IncomingHtlc)?.add }
fun Iterable<DirectedHtlc>.outgoings(): List<UpdateAddHtlc> = mapNotNull { (it as? OutgoingHtlc)?.add }

data class CommitmentSpec(
    val htlcs: Set<DirectedHtlc>,
    val feerate: FeeratePerKw,
    val toLocal: MilliSatoshi,
    val toRemote: MilliSatoshi
) {
    fun findIncomingHtlcById(id: Long): IncomingHtlc? = htlcs.find { it is IncomingHtlc && it.add.id == id } as IncomingHtlc?

    fun findOutgoingHtlcById(id: Long): OutgoingHtlc? = htlcs.find { it is OutgoingHtlc && it.add.id == id } as OutgoingHtlc?

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
                    is UpdateFee -> spec.copy(feerate = u.feeratePerKw)
                    else -> spec
                }
            })
            return spec5
        }
    }
}
