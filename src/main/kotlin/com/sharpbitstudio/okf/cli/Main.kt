@file:JvmName("Main")

package com.sharpbitstudio.okf.cli

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** `okf <index|check|impact|xcheck|version> [options]` — see [OkfCli]. Output is UTF-8 (GitHub Actions reads workflow commands as UTF-8). */
fun main(args: Array<String>) {
    val out = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)
    val err = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
    val code = OkfCli(out, err).run(args.toList())
    out.flush()
    err.flush()
    exitProcess(code)
}
