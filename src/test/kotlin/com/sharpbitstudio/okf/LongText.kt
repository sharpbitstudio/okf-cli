package com.sharpbitstudio.okf

import org.junit.jupiter.api.fail

/** 1,048,576 characters of base64-like filler: no whitespace, no parentheses (a long bare link target). */
internal val MIB: String = "iVBORw0KGgoAAAANSUhEUgAA+/=".let { it.repeat((1 shl 20) / it.length + 1).take(1 shl 20) }

/**
 * Passes when [expected] equals [actual]; otherwise fails with the lengths of both texts, the first index where they
 * differ and up to 40 characters around it from each, so a megabyte value never lands whole in the failure message.
 */
internal fun assertSameText(expected: Any?, actual: Any?) {
    if (expected == actual) return
    val want = expected.toString()
    val got = actual.toString()
    if (want == got) fail("values differ, their texts are equal (${want.length} characters)")
    var at = 0
    while (at < want.length && at < got.length && want[at] == got[at]) at++
    fun around(text: String) = text.substring(maxOf(0, at - 20), minOf(text.length, at + 20))
    fail(
        "texts differ: expected ${want.length} characters, actual ${got.length}; first difference at index $at; " +
            "expected around it <${around(want)}>, actual <${around(got)}>",
    )
}
