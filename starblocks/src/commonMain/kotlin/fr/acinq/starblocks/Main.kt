package fr.acinq.starblocks

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.http.*

fun main(args: Array<String>) = Starblocks().main(args)

class Starblocks: CliktCommand() {
    private val phoenixdUrl: Url by option("--phoenixd", help = "Phoenixd API endoint").convert { Url(it) }.default(Url("http://127.0.0.1:8080"))

    override fun run() {
        Api(phoenixdUrl).server.start(wait = true)
    }

}