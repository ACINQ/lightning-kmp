package fr.acinq.lightning.logging

import co.touchlab.kermit.Logger

fun Logger.appendingTag(tag: String): Logger {
    return withTag("${this.tag}.${tag}")
}

inline fun Logger.verbose(throwable: Throwable? = null, tag: String = this.tag, message: () -> String){
    this.v(throwable, tag, message)
}

inline fun Logger.debug(throwable: Throwable? = null, tag: String = this.tag, message: () -> String){
    this.d(throwable, tag, message)
}

inline fun Logger.info(throwable: Throwable? = null, tag: String = this.tag, message: () -> String){
    this.i(throwable, tag, message)
}

inline fun Logger.warning(throwable: Throwable? = null, tag: String = this.tag, message: () -> String){
    this.w(throwable, tag, message)
}

inline fun Logger.error(throwable: Throwable? = null, tag: String = this.tag, message: () -> String){
    this.e(throwable, tag, message)
}

inline fun Logger.always(throwable: Throwable? = null, tag: String = this.tag, message: () -> String){
    this.a(throwable, tag, message)
}
