package com.sharpbitstudio.okf

/**
 * Runs [body] on a new thread whose stack is [kib] KiB and returns its result; whatever [body] threw (an error such as
 * `StackOverflowError` included) is rethrown on the calling thread. The JVM may raise a stack below its platform
 * minimum to that minimum, never lower it.
 */
internal fun <T> onStack(kib: Int, body: () -> T): T {
    var result: Result<T>? = null
    val thread = Thread(null, { result = runCatching(body) }, "stack-$kib-KiB", kib.toLong() shl 10)
    thread.start()
    thread.join()
    return checkNotNull(result).getOrThrow()
}
