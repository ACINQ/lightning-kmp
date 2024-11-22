package fr.acinq.lightning.db.serializers

import fr.acinq.lightning.db.serializers.primitives.AbstractStringSerializer
import fr.acinq.lightning.payment.Bolt11Invoice

class Bolt11InvoiceSerializer : AbstractStringSerializer<Bolt11Invoice>(
    name = "Bolt11Invoice",
    fromString = { Bolt11Invoice.read(it).get() },
    toString = Bolt11Invoice ::write
)
