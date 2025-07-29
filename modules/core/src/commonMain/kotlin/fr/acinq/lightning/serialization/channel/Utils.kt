package fr.acinq.lightning.serialization.channel

import fr.acinq.lightning.channel.Commitments
import fr.acinq.lightning.transactions.DirectedHtlc

/**
 * When multiple commitments are active, htlcs are shared between all of these commitments, so we serialize the htlcs only once
 * to save space. The direction we use is from our local point of view: we use a set, which deduplicates htlcs that are in both
 * local and remote commitments, or in multiple commitments.
 */
internal val Commitments.allHtlcs: Set<DirectedHtlc> get() = this.run {
    buildSet {
        // All active commitments have the same htlc set, so we only consider the first one
        addAll(active.first().localCommit.spec.htlcs)
        addAll(active.first().remoteCommit.spec.htlcs.map { htlc -> htlc.opposite() })
        active.first().nextRemoteCommit?.let { addAll(it.spec.htlcs.map { htlc -> htlc.opposite() }) }
        // Each inactive commitment may have a distinct htlc set
        inactive.forEach { c ->
            addAll(c.localCommit.spec.htlcs)
            addAll(c.remoteCommit.spec.htlcs.map { htlc -> htlc.opposite() })
            c.nextRemoteCommit?.let { addAll(it.spec.htlcs.map { htlc -> htlc.opposite() }) }
        }
    }
}