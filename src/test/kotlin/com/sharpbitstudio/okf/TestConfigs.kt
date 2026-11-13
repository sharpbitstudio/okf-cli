package com.sharpbitstudio.okf

/** The settings the fixtures and goldens of these suites were written with, spelled out so they do not depend on the okf.yaml defaults. */
internal object TestConfigs {
    const val INDEX_COMMAND = "./gradlew okfIndex"
    val SR = OkfConfig(
        repo = "test-repo",
        indexCommand = INDEX_COMMAND,
        indexLanguage = IndexLanguage.SR,
        adrReadmeLink = AdrReadmeLink.ALWAYS,
    )
}
