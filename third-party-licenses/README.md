# Third-party licences

Licence texts of the third-party components bundled in the self-contained `okf-cli-<version>-all.jar`, which
carries this directory as `META-INF/licenses/`. Each file is the verbatim upstream text, fetched from the tag of the
exact version the build resolves (`gradle/libs.versions.toml`; JetBrains Annotations comes in through the Kotlin
Standard Library). The thin and sources jars bundle no third-party code. Re-check this inventory after every bump of
a runtime dependency.

| Component | Version | Licence | File | Source |
|---|---|---|---|---|
| Kotlin Standard Library | 2.4.21 | Apache-2.0 | `kotlin-stdlib/LICENSE.txt` | https://github.com/JetBrains/kotlin/blob/v2.4.21/license/LICENSE.txt |
| Kotlin (upstream NOTICE) | 2.4.21 | Apache-2.0 §4(d) | `kotlin-stdlib/NOTICE.txt` | https://github.com/JetBrains/kotlin/blob/v2.4.21/license/NOTICE.txt |
| ↳ `kotlin/collections`, derived from GWT, (C) 2007-08 Google Inc. | | Apache-2.0 | `kotlin-stdlib/gwt_license.txt` | https://github.com/JetBrains/kotlin/blob/v2.4.21/license/third_party/gwt_license.txt |
| ↳ `kotlin/time`, derived from ThreeTen backport, Copyright (c) 2007-present, Stephen Colebourne & Michael Nascimento Santos | | BSD-3-Clause | `kotlin-stdlib/threetenbp_license.txt` | https://github.com/JetBrains/kotlin/blob/v2.4.21/license/third_party/threetenbp_license.txt |
| ↳ `kotlin/util/UnsignedJVM.kt`, derived from Guava's UnsignedLongs, (C) 2011 The Guava Authors | | Apache-2.0 | `kotlin-stdlib/guava_license.txt` | https://github.com/JetBrains/kotlin/blob/v2.4.21/license/third_party/guava_license.txt |
| ↳ `kotlin/util/MathJVM.kt`, derived from boost special math functions, Copyright Eric Ford & Hubert Holin 2001 | | BSL-1.0 | `kotlin-stdlib/boost_LICENSE.txt` | https://github.com/JetBrains/kotlin/blob/v2.4.21/license/third_party/boost_LICENSE.txt |
| JetBrains Annotations | 13.0 | Apache-2.0 | `jetbrains-annotations/LICENSE-2.0.txt` | https://www.apache.org/licenses/LICENSE-2.0.txt |
| SnakeYAML | 2.7 | Apache-2.0 | `snakeyaml/LICENSE.txt` | https://codeberg.org/snakeyaml/snakeyaml/src/tag/snakeyaml-2.7/LICENSE.txt |

Notes:

- Kotlin Standard Library: the third-party entries are those of
  https://github.com/JetBrains/kotlin/blob/v2.4.21/license/README.md whose path is part of the JVM standard library
  (`libraries/stdlib/src`, `libraries/stdlib/jvm/src`); the JS, Wasm and Native entries are not in the JVM jar.
  The upstream `license/NOTICE.txt` names the Kotlin Compiler distribution; it is carried as well, as a precaution,
  because the standard library is built from the same repository under the same licence.
- JetBrains Annotations 13.0 was built from the IntelliJ Community repository and has no tagged licence file of its
  own; its POM (`org.jetbrains:annotations:13.0`) declares "The Apache Software License, Version 2.0" with the URL
  http://www.apache.org/licenses/LICENSE-2.0.txt, so the file is the canonical Apache-2.0 text from that URL.
- SnakeYAML 2.7 contains code derived from Google GData (`org.yaml.snakeyaml.external.com.google.gdata`, Copyright
  2008 Google Inc.), under Apache-2.0 per its source headers; the SnakeYAML source tree at that tag has no NOTICE
  file and no other licence file.
