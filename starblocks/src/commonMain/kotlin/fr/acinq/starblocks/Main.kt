package fr.acinq.starblocks

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import io.ktor.http.*
import okio.Path
import okio.Path.Companion.toPath

fun main(args: Array<String>) = Starblocks().main(args)

class Starblocks: CliktCommand() {
    private val phoenixdUrl: Url by option("--phoenixd", help = "Phoenixd API endoint").convert { Url(it) }.default(Url("http://127.0.0.1:8080"))
    private val webDir: Path by option("--webdir", help = "directory of the starblocks website")
        .convert { it.toPath() }
        .default("web".toPath())
        .check("webdir does not exist!") { fileSystem.exists(it) }

    override fun run() {
        Api(phoenixdUrl, webDir).server.start(wait = true)
    }

}