package fr.acinq.starblocks

import io.ktor.client.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import io.ktor.server.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Api(phoenixdUrl: Url) {

    private var customers = emptyMap<Int, Customer>()

    val client = HttpClient(io.ktor.client.engine.cio.CIO)

    val server = embeddedServer(CIO, port = 8081, host = "0.0.0.0") {

        val json = Json {
            prettyPrint = true
            isLenient = true
        }

        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respondText(text = cause.message ?: "", status = defaultExceptionStatusCode(cause) ?: HttpStatusCode.InternalServerError)
            }
        }

        routing {
            get("/") {
                call.respondText("Hello World!")
            }
            post("/customer") {
                val customer = call.receive<Customer>()
                customers = customers + (customer.id to customer)
                call.respondText("Customer stored correctly", status = HttpStatusCode.Created)
                call.respond(HttpStatusCode.Created, customer)
            }
            get("/customer/{id}") {
                val id = call.parameters["id"]!!.toInt()
                val customer: Customer = customers[id]!!
                call.respond(customer)
            }
            webSocket("/websocket") {
                customers.values.forEach { sendSerialized(it) }
            }
            get("/invoice") {
                // forward create invoice to phoenixd
                val invoice = client.submitForm(
                    url = url {
                        takeFrom(phoenixdUrl)
                        path("invoice")
                    },
                    formParameters = parameters {
                        append("amountSat", 42000.toString())
                        append("description", "my description")
                    }
                ).bodyAsText()
                call.respondText(invoice)
            }
        }
    }
}

@Serializable
data class Customer(val id: Int, val firstName: String, val lastName: String)

