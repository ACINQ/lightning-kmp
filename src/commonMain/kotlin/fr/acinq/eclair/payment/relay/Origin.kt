package fr.acinq.eclair.payment.relay

import fr.acinq.eclair.utils.UUID
import kotlinx.serialization.Serializable

@Serializable
sealed class Origin {
    /** Our node is the origin of the payment. */
    @Serializable
    data class Local(val id: UUID) : Origin() // we don't persist reference to local actors
}
