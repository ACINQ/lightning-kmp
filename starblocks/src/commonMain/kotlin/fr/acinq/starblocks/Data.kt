/*
 * Copyright 2024 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("PropertyName")

package fr.acinq.starblocks

import kotlinx.serialization.Serializable

@Serializable
data class Product(
    val id: String,
    val label: String,
    val name: String,
    val price_satoshi: Long,
    val description: String,
)

@Serializable
data class OrderProductLine(
    val product_id: String,
    val count: Int,
    val unitary_price_satoshi: Long,
    val product_name: String,
)

@Serializable
data class Order(
    val id: String,
    val items: List<OrderProductLine>,
    val payment_request: String,
    val payment_hash: String,
    val paid: Boolean,
)

@Serializable
data class ApiError(val code: Int, val message: String)

// --- objects from phoenixd

@Serializable
data class GeneratedInvoice(val amountMsat: Long?, val paymentHash: String, val serialized: String)

@Serializable
data class PaymentReceived(val amountMsat: Long, val paymentHash: String)

// --- fake database

object Database {
    val products = listOf(
        Product(
            id = "pr_ipfxrb9",
            label = "coffee_1",
            name = "Blockaccino",
            price_satoshi = 1,
            description = "Truly the height of our baristas craft.",
        ),
        Product(
            id = "pr_rajejrj",
            label = "coffee_2",
            name = "Espresso Coin Panna",
            price_satoshi = 500,
            description = "Espresso meets a dollop of whipped cream.",
        ),
        Product(
            id = "pr_j4hb2an",
            label = "coffee_3",
            name = "Scala Chip Frappuccino",
            price_satoshi = 5_000,
            description = "A lighter chip off the java block.",
        ),
        Product(
            id = "pr_nnvr23f",
            label = "coffee_4",
            name = "Half a Ton of Coffee",
            price_satoshi = 250_000,
            description = "If you really, really like coffee.",
        )
    )
}
