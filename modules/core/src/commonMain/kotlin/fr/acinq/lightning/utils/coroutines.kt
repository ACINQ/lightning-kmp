package fr.acinq.lightning.utils

import kotlinx.coroutines.Job
import kotlinx.coroutines.job

private fun Job.print(buffer: StringBuilder, prefix: String, childrenPrefix: String) {
    buffer.append(prefix)
    buffer.append(job.toString())
    buffer.append('\n')
    val it = job.children.iterator()
    while (it.hasNext()) {
        val child = it.next()
        if (it.hasNext()) {
            child.print(buffer, "$childrenPrefix├── ", "$childrenPrefix│   ")
        } else {
            child.print(buffer, "$childrenPrefix└── ", "$childrenPrefix    ")
        }
    }
}

/**
 * Extension method to print a hierarchy of coroutines for debug purposes.
 * Usage:
 * ```
 *    println(scope.coroutineContext.job.mkTree())
 * ```
 */
fun Job.mkTree(): String {
    val sb = StringBuilder()
    job.print(sb, "", "")
    return sb.toString()
}