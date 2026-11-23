package com.sharpbitstudio.okf

import org.junit.jupiter.api.fail

/**
 * Runs [body] on its own daemon thread and returns its result, or fails when it has not finished within [seconds];
 * whatever [body] threw is rethrown on the calling thread. A body that runs over is left running: as a daemon thread it
 * ends with the JVM at the latest, and never keeps the JVM from exiting.
 */
internal fun <T> withinSeconds(seconds: Long, body: () -> T): T {
    var result: Result<T>? = null
    val thread = Thread({ result = runCatching(body) }, "within-$seconds-s")
    thread.isDaemon = true
    thread.start()
    thread.join(seconds * 1_000)
    if (thread.isAlive) fail("did not finish within $seconds s")
    return checkNotNull(result).getOrThrow()
}
