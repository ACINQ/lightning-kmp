package fr.acinq.starblocks

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.date.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import okio.*

expect val fileSystem: FileSystem

// source:
// https://github.com/luca992/multiplatform-static-content-ktor-server/blob/6be12de7db4ec6c3a0167cb7c019e31a259c3443/server/src/commonMain/kotlin/io/eqoty/server/plugins/OkioRoute.kt
// also see:
// https://slack-chats.kotlinlang.org/t/2527462/how-do-i-setup-static-routing-with-the-native-version-of-kto
// https://gist.github.com/Stexxe/4867bbd9b44339f9f9adc39e166894ca
fun Route.files(folder: String) {
    val dir = staticRootFolder?.resolve(folder) ?: return
    val pathParameter = "static-content-path-parameter"
    get("{$pathParameter...}") {
        val relativePath = call.parameters.getAll(pathParameter)?.joinToString(Path.DIRECTORY_SEPARATOR) ?: return@get
        val file = dir.resolve(relativePath)
        call.respondStatic(file)
    }
}

fun Route.default(localPath: String) {
    val path = staticRootFolder?.resolve(localPath) ?: return
    get {
        call.respondStatic(path)
    }
}

private val staticRootFolderKey = AttributeKey<Path>("BaseFolder")

var Route.staticRootFolder: Path?
    get() = attributes.getOrNull(staticRootFolderKey) ?: parent?.staticRootFolder
    set(value) {
        if (value != null) {
            attributes.put(staticRootFolderKey, value)
        } else {
            attributes.remove(staticRootFolderKey)
        }
    }

fun Route.file(remotePath: String, localPath: String) {
    val path = staticRootFolder?.resolve(localPath) ?: return

    get(remotePath) {
        call.respondStatic(path)
    }
}

suspend inline fun ApplicationCall.respondStatic(path: Path) {
    if (fileSystem.exists(path)) {
        respond(LocalFileContent(path, ContentType.defaultForFile(path)))
    }
}

fun ContentType.Companion.defaultForFile(path: Path): ContentType =
    ContentType.fromFileExtension(path.name.substringAfter('.', path.name)).selectDefault()

fun List<ContentType>.selectDefault(): ContentType {
    val contentType = firstOrNull() ?: ContentType.Application.OctetStream
    return when {
        contentType.contentType == "text" && contentType.charset() == null -> contentType.withCharset(Charsets.UTF_8)
        else -> contentType
    }
}

class LocalFileContent(
    private val path: Path,
    override val contentType: ContentType = ContentType.defaultForFile(path)
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long get() = stat().size ?: -1
    override suspend fun writeTo(channel: ByteWriteChannel) {
        val source = fileSystem.source(path)
        source.use { fileSource ->
            fileSource.buffer().use { bufferedFileSource ->
                val buf = ByteArray(4 * 1024)
                while (true) {
                    val read = bufferedFileSource.read(buf)
                    if (read <= 0) break
                    channel.writeFully(buf, 0, read)
                }
            }
        }
    }

    init {
        if (!fileSystem.exists(path)) {
            throw IllegalStateException("No such file ${path.normalized()}")
        }

        stat().lastModifiedAtMillis?.let {
            versions += LastModifiedVersion(GMTDate(it))
        }
    }

    private fun stat(): FileMetadata {
        return fileSystem.metadata(path)
    }
}