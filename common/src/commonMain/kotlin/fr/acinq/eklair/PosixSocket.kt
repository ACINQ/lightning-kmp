package fr.acinq.eklair

import platform.Foundation.*
import platform.posix.*
//import platform.darwin.*

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlin.coroutines.intrinsics.*

private fun Int.isMinusOne() = (this == -1)
private fun Long.isMinusOne() = (this == -1L)
private fun ULong.isMinusOne() = (this == ULong.MAX_VALUE)

fun connect(host: String, port: Int) {
    memScoped {

    }
}

//        val ip = IP.fromHost(host)
//
//        val address = allocArray<sockaddr_in>(1)
////        address.set(ip, port)
//        with(address) {
//            memset(this.ptr, 0, sockaddr_in.size.convert())
//            sin_family = AF_INET.convert()
//            sin_addr.s_addr = posix_htons(0).convert()
//            sin_port = posix_htons(port).convert()
//        }.
//
//        val connected = connect(sockfd, addr as CValuesRef<sockaddr>?, sockaddr_in.size.convert())
//        endpoint = Endpoint(ip, port)
//        setSocketBlockingEnabled(false)
//        if (connected != 0) {
//            _connected = false
//            error("Can't connect to $ip:$port ('$host')")
//        }
//        _connected = true

//class IP(val data: UByteArray) {
//    constructor(v0: Int, v1: Int, v2: Int, v3: Int) : this(
//            ubyteArrayOf(
//                    v0.toUByte(),
//                    v1.toUByte(),
//                    v2.toUByte(),
//                    v3.toUByte()
//            )
//    )
//
//    val v0 get() = data[0]
//    val v1 get() = data[1]
//    val v2 get() = data[2]
//    val v3 get() = data[3]
//    val str get() = "$v0.$v1.$v2.$v3"
//    val value: Int get() = (v0.toInt() shl 0) or (v1.toInt() shl 8) or (v2.toInt() shl 16) or (v3.toInt() shl 24)
//    //val value: Int get() = (v0.toInt() shl 24) or (v1.toInt() shl 16) or (v2.toInt() shl 8) or (v3.toInt() shl 0)
//    override fun toString(): String = str
//
//    companion object {
//        fun fromHost(host: String): IP {
//            val hname = gethostbyname(host)
//            val inetaddr = hname!!.pointed.h_addr_list!![0]!!
//            return IP(
//                    ubyteArrayOf(
//                            inetaddr[0].toUByte(),
//                            inetaddr[1].toUByte(),
//                            inetaddr[2].toUByte(),
//                            inetaddr[3].toUByte()
//                    )
//            )
//        }
//    }
//}

//fun connect(val ip: IP, val port: Int) {
//    memScoped {
//
//        val clientAddr = alloc<sockaddr_in>()
//        val listenFd = socket(AF_INET, SOCK_STREAM, 0)
//                .ensureUnixCallResult { !it.isMinusOne() }
//
//        with(serverAddr) {
//            memset(this.ptr, 0, sockaddr_in.size.convert())
//            sin_family = AF_INET.convert()
//            sin_addr.s_addr = posix_htons(0).convert()
//            sin_port = posix_htons(port).convert()
//        }
//
//        bind(listenFd, serverAddr.ptr.reinterpret(), sockaddr_in.size.toUInt())
//                .ensureUnixCallResult { it == 0 }
//
//        fcntl(listenFd, F_SETFL, O_NONBLOCK)
//                .ensureUnixCallResult { it == 0 }
//
//        listen(listenFd, 10)
//                .ensureUnixCallResult { it == 0 }

//        var connectionId = 0
//        acceptClientsAndRun(listenFd) {
//            memScoped {
//                val bufferLength = 100uL
//                val buffer = allocArray<ByteVar>(bufferLength.toLong())
//                val connectionIdString = "#${++connectionId}: ".cstr
//                val connectionIdBytes = connectionIdString.ptr
//
//                try {
//                    while (true) {
//                        val length = read(buffer, bufferLength)
//
//                        if (length == 0uL)
//                            break
//
//                        write(connectionIdBytes, connectionIdString.size.toULong())
//                        write(buffer, length)
//                    }
//                } catch (e: IOException) {
//                    println("I/O error occured: ${e.message}")
//                }
//            }
//        }
//    }
//}

//sealed class WaitingFor {
//    class Accept : WaitingFor()
//
//    class Read(val data: CArrayPointer<ByteVar>,
//               val length: ULong,
//               val continuation: Continuation<ULong>) : WaitingFor()
//
//    class Write(val data: CArrayPointer<ByteVar>,
//                val length: ULong,
//                val continuation: Continuation<Unit>) : WaitingFor()
//}
//
//class Client(val clientFd: Int, val waitingList: MutableMap<Int, WaitingFor>) {
//    suspend fun read(data: CArrayPointer<ByteVar>, dataLength: ULong): ULong {
//        val length = read(clientFd, data, dataLength)
//        if (length >= 0)
//            return length.toULong()
//        if (posix_errno() != EWOULDBLOCK)
//            throw IOException(getUnixError())
//        // Save continuation and suspend.
//        return suspendCoroutine { continuation ->
//            waitingList.put(clientFd, WaitingFor.Read(data, dataLength, continuation))
//        }
//    }
//
//    suspend fun write(data: CArrayPointer<ByteVar>, length: ULong) {
//        val written = write(clientFd, data, length)
//        if (written >= 0)
//            return
//        if (posix_errno() != EWOULDBLOCK)
//            throw IOException(getUnixError())
//        // Save continuation and suspend.
//        return suspendCoroutine { continuation ->
//            waitingList.put(clientFd, WaitingFor.Write(data, length, continuation))
//        }
//    }
//}
//
//open class EmptyContinuation(override val context: CoroutineContext = EmptyCoroutineContext) : Continuation<Any?> {
//    companion object : EmptyContinuation()
//    override fun resumeWith(result: Result<Any?>) { result.getOrThrow() }
//}
//
//fun acceptClientsAndRun(serverFd: Int, block: suspend Client.() -> Unit) {
//    memScoped {
//        val waitingList = mutableMapOf<Int, WaitingFor>(serverFd to WaitingFor.Accept())
//        val readfds = alloc<fd_set>()
//        val writefds = alloc<fd_set>()
//        val errorfds = alloc<fd_set>()
//        var maxfd = serverFd
//        while (true) {
//            posix_FD_ZERO(readfds.ptr)
//            posix_FD_ZERO(writefds.ptr)
//            posix_FD_ZERO(errorfds.ptr)
//            for ((socketFd, watingFor) in waitingList) {
//                when (watingFor) {
//                    is WaitingFor.Accept -> posix_FD_SET(socketFd, readfds.ptr)
//                    is WaitingFor.Read   -> posix_FD_SET(socketFd, readfds.ptr)
//                    is WaitingFor.Write  -> posix_FD_SET(socketFd, writefds.ptr)
//                }
//                posix_FD_SET(socketFd, errorfds.ptr)
//            }
//            pselect(maxfd + 1, readfds.ptr, writefds.ptr, errorfds.ptr, null, null)
//                    .ensureUnixCallResult { it >= 0 }
//            loop@for (socketFd in 0..maxfd) {
//                val waitingFor = waitingList[socketFd]
//                val errorOccured = posix_FD_ISSET(socketFd, errorfds.ptr) != 0
//                if (posix_FD_ISSET(socketFd, readfds.ptr) != 0
//                        || posix_FD_ISSET(socketFd, writefds.ptr) != 0
//                        || errorOccured) {
//                    when (waitingFor) {
//                        is WaitingFor.Accept -> {
//                            if (errorOccured)
//                                throw Error("Socket has been closed externally")
//
//                            // Accept new client.
//                            val clientFd = accept(serverFd, null, null)
//                            if (clientFd.isMinusOne()) {
//                                if (posix_errno() != EWOULDBLOCK)
//                                    throw Error(getUnixError())
//                                break@loop
//                            }
//                            fcntl(clientFd, F_SETFL, O_NONBLOCK)
//                                    .ensureUnixCallResult { it == 0 }
//                            if (maxfd < clientFd)
//                                maxfd = clientFd
//                            block.startCoroutine(Client(clientFd, waitingList), EmptyContinuation)
//                        }
//                        is WaitingFor.Read -> {
//                            if (errorOccured)
//                                waitingFor.continuation.resumeWithException(IOException("Connection was closed by peer"))
//
//                            // Resume reading operation.
//                            waitingList.remove(socketFd)
//                            val length = read(socketFd, waitingFor.data, waitingFor.length)
//                            if (length < 0) // Read error.
//                                waitingFor.continuation.resumeWithException(IOException(getUnixError()))
//                            waitingFor.continuation.resume(length.toULong())
//                        }
//                        is WaitingFor.Write -> {
//                            if (errorOccured)
//                                waitingFor.continuation.resumeWithException(IOException("Connection was closed by peer"))
//
//                            // Resume writing operation.
//                            waitingList.remove(socketFd)
//                            val written = write(socketFd, waitingFor.data, waitingFor.length)
//                            if (written < 0) // Write error.
//                                waitingFor.continuation.resumeWithException(IOException(getUnixError()))
//                            waitingFor.continuation.resume(Unit)
//                        }
//                    }
//                }
//            }
//        }
//    }
//}
//
//class IOException(message: String): RuntimeException(message)
//
//fun getUnixError() = strerror(posix_errno())!!.toKString()
//
//inline fun Int.ensureUnixCallResult(predicate: (Int) -> Boolean): Int {
//    if (!predicate(this)) {
//        throw Error(getUnixError())
//    }
//    return this
//}
//
//inline fun Long.ensureUnixCallResult(predicate: (Long) -> Boolean): Long {
//    if (!predicate(this)) {
//        throw Error(getUnixError())
//    }
//    return this
//}
//
//inline fun ULong.ensureUnixCallResult(predicate: (ULong) -> Boolean): ULong {
//    if (!predicate(this)) {
//        throw Error(getUnixError())
//    }
//    return this
//}
//
