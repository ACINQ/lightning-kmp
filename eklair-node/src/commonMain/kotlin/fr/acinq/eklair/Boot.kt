package fr.acinq.eklair

expect object Boot{
    fun main(args: Array<String>)
}

@ExperimentalStdlibApi
class Eklair{
    fun run(){
        Boot.main(emptyArray())
    }
}
