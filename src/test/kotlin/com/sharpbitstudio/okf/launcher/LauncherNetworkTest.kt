package com.sharpbitstudio.okf.launcher

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The launcher against a published release over the network (`./gradlew networkTest`, not part of `check`): real `sh`,
 * real `curl` (linked into the toolbox), real Java (the test JVM's, through `JAVA_HOME`), an empty cache. The release is
 * `1.0.0-alpha.1`, whose version and SHA-256 are public and immutable. GitHub tokens are removed from every child.
 */
@Tag("network")
class LauncherNetworkTest {
    @TempDir
    lateinit var tmp: File

    @Test
    fun `an empty cache downloads the pinned release, which then runs offline`() {
        val box = LauncherSandbox(tmp)
        box.launcher()
        box.pin("version=$VERSION\nsha256=$SHA256\n")
        val env = mapOf("JAVA_HOME" to with(box) { File(System.getProperty("java.home")).shell })

        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool + "curl")
        val first = box.okfw("version", env = env).lf()
        assertEquals(Run(0, "okf-cli $VERSION\n", "okfw: downloading okf-cli $VERSION\n"), first)
        assertEquals(SHA256, LauncherSandbox.sha256(box.cachedJar(VERSION).readBytes()))

        // Offline: curl now fails if called, and is not called.
        box.toolbox(LauncherSandbox.BASE_TOOLS + Host.shaTool)
        box.curl(serve = null)
        assertEquals(Run(0, "okf-cli $VERSION\n", ""), box.okfw("version", env = env).lf())
        assertEquals(emptyList<List<String>>(), box.calls())
    }

    private companion object {
        const val VERSION = "1.0.0-alpha.1"
        const val SHA256 = "cadfdb7cb0d55e1b615e2662cca6cd48c554463f8c3e212753ceafea0303503b"
    }
}
