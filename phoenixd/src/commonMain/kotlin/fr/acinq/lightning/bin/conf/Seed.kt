package fr.acinq.lightning.bin.conf

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.MnemonicCode
import fr.acinq.lightning.Lightning.randomBytes
import fr.acinq.lightning.utils.toByteVector
import okio.FileSystem
import okio.Path

fun getOrGenerateSeed(dir: Path): ByteVector {
    val file = dir / "seed.dat"
    val mnemonics = if (FileSystem.SYSTEM.exists(file)) {
        FileSystem.SYSTEM.read(file) { readUtf8() }
    } else {
        println("generating new seed")
        val entropy = randomBytes(16)
        val mnemonics = MnemonicCode.toMnemonics(entropy).joinToString(" ")
        FileSystem.SYSTEM.write(file) { writeUtf8(mnemonics) }
        mnemonics
    }
    return MnemonicCode.toSeed(mnemonics, "").toByteVector()
}