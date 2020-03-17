package fr.acinq.eklair

actual object Boot{
    actual fun main(args: Array<String>) {
        println(Hex.encode(byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xbe.toByte(), 0xef.toByte())))
    }
}
