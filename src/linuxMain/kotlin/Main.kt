import fr.acinq.bitcoin.PrivateKey

fun main() {
    val priv = PrivateKey.fromHex("0101010101010101010101010101010101010101010101010101010101010101")
    val pub = priv.publicKey()
    println("pub = $pub")
}