package okf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RepoIndexTest {
    private val index = RepoIndex(
        listOf(
            "build.gradle.kts",
            "server/build.gradle.kts",
            "server/src/main/java/com/example/plaza/App.java",
            "server/src/main/java/com/example/plaza/otp/internal/jobs/OtpExpiryJob.java",
            "server/src/test/java/com/example/plaza/otp/OtpTest.java",
            "server/src/main/resources/db/migration/otp/V1__otp.sql",
            "buildSrc/src/main/kotlin/okf/OkfChecker.kt",
        ),
    )

    @Test
    fun `directories are derived from file paths`() {
        assertTrue(index.isDir("server/src/main/java/com/example/plaza/otp/internal"))
        assertTrue(index.isFile("server/build.gradle.kts"))
        assertFalse(index.isDir("server/build.gradle.kts"))
        assertFalse(index.isFile("server"))
    }

    @Test
    fun `suffix lookup matches whole segments only`() {
        assertEquals(listOf("build.gradle.kts", "server/build.gradle.kts"), index.filesWithSuffix("build.gradle.kts").sorted())
        assertEquals(1, index.filesWithSuffix("internal/jobs/OtpExpiryJob.java").size)
        assertEquals(emptyList<String>(), index.filesWithSuffix("ExpiryJob.java"))
        assertEquals(listOf("server/src/main/java/com/example/plaza/otp/internal/jobs"), index.dirsWithSuffix("otp/internal/jobs"))
    }

    @Test
    fun `package roots are source roots plus the descended base package of code roots`() {
        assertEquals(
            listOf(
                "buildSrc/src/main/kotlin",
                "buildSrc/src/main/kotlin/okf",
                "server/src/main/java",
                "server/src/main/java/com/example/plaza",
                "server/src/main/resources",
                "server/src/test/java",
                "server/src/test/java/com/example/plaza/otp",
            ),
            index.packageRoots.sorted(),
        )
    }

    @Test
    fun `line count ignores a trailing newline and CRLF`() {
        assertEquals(0, RepoIndex.countLines(""))
        assertEquals(2, RepoIndex.countLines("a\nb"))
        assertEquals(2, RepoIndex.countLines("a\r\nb\r\n"))
        assertEquals(3, RepoIndex.countLines("a\n\nb\n"))
    }
}
