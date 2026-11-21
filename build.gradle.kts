import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

/** CLI entry point (`@file:JvmName("Main")` in com/sharpbitstudio/okf/cli/Main.kt). */
val cliMainClass = "com.sharpbitstudio.okf.cli.Main"

description = "OKF (Open Knowledge Format) v0.2 docs-bundle CLI: index, check, impact, xcheck."

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.snakeyaml)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Built on JDK 25, but the bytecode targets Java 17: consumers may run the fat jar on Java 17.
// Kotlin and Java targets are kept equal so the Kotlin Gradle plugin's JVM-target consistency check passes.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Compile against the Java 17 API, not just emit 17 bytecode.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// `okf version` prints the version the jar was built with. The launcher (`launcher/okfw`, `launcher/okfw.cmd`) is
// embedded, so `okf wrapper` and `okf init` write the copy this jar was built with (docs/launcher.md).
tasks.processResources {
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    filesMatching("com/sharpbitstudio/okf/version.properties") {
        expand("version" to projectVersion)
    }
    from(layout.projectDirectory.dir("launcher")) {
        into("com/sharpbitstudio/okf/launcher")
    }
}

// LICENSE and NOTICE go into META-INF/ of every jar (thin, sources, all).
tasks.withType<Jar>().configureEach {
    metaInf {
        from(layout.projectDirectory.file("LICENSE"), layout.projectDirectory.file("NOTICE"))
    }
}

// The thin jar is not runnable on its own (no Main-Class): only the `all` jar bundles the runtime classpath.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
        )
    }
}

// Self-contained CLI jar (`okf-cli-<v>-all.jar`) for repos without Gradle: main output plus the runtime
// classpath (Kotlin stdlib with JetBrains Annotations, SnakeYAML) unpacked into one jar, with the licences of those
// bundled components (third-party-licenses/) under META-INF/licenses/.
val allJar = tasks.register<Jar>("allJar") {
    group = "build"
    description = "Assembles the self-contained CLI jar (classifier `all`)."
    archiveClassifier.set("all")
    manifest {
        attributes(
            "Main-Class" to cliMainClass,
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { classpath -> classpath.map { if (it.isDirectory) it else zipTree(it) } })
    into("META-INF/licenses") {
        from(layout.projectDirectory.dir("third-party-licenses"))
    }
    // Signature files of the merged jars would no longer match and make the JVM reject the jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
    // Dead weight in a CLI jar: versioned classes need a Multi-Release manifest (this one has none), and
    // `*.kotlin_module` only serves the Kotlin compiler compiling against the jar.
    exclude("META-INF/versions/**", "META-INF/*.kotlin_module")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.assemble {
    dependsOn(allJar)
}

/**
 * System properties of the test JVMs: `okf.repoRoot` (this checkout, for the committed `launcher/`, `action/`,
 * `action.yml` and `xcheck/`) and `okf.allJar` (this build's `all` jar, which LauncherJarTest runs through the
 * launcher). The jar is an input, so `test` builds it first.
 */
abstract class LauncherTestProperties : CommandLineArgumentProvider {
    @get:Internal
    abstract val repoRoot: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allJar: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf("-Dokf.repoRoot=${repoRoot.get()}", "-Dokf.allJar=${allJar.get().asFile.absolutePath}")
}

fun Test.launcherTestProperties() {
    val jar = allJar.flatMap { it.archiveFile }
    inputs.dir(layout.projectDirectory.dir("launcher")).withPropertyName("launcher").withPathSensitivity(PathSensitivity.RELATIVE)
    // The GitHub Action's files, which the action tests read from okf.repoRoot.
    inputs.dir(layout.projectDirectory.dir("action")).withPropertyName("action").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("action.yml")).withPropertyName("actionYml").withPathSensitivity(PathSensitivity.RELATIVE)
    // The xcheck action's files (xcheck/action.yml and its scripts), which XcheckActionTest runs from okf.repoRoot.
    inputs.dir(layout.projectDirectory.dir("xcheck")).withPropertyName("xcheck").withPathSensitivity(PathSensitivity.RELATIVE)
    jvmArgumentProviders.add(
        objects.newInstance<LauncherTestProperties>().apply {
            repoRoot.set(layout.projectDirectory.asFile.absolutePath)
            allJar.set(jar)
        },
    )
}

// `network` tests download a published release and check its provenance with the real gh (LauncherNetworkTest); they
// run in `networkTest` only, not in `check`.
tasks.test {
    useJUnitPlatform {
        excludeTags("network")
    }
    launcherTestProperties()
}

tasks.register<Test>("networkTest") {
    group = "verification"
    description = "Runs the tests tagged `network`: the launcher against a published release, with real curl, and its " +
        "--update / --verify provenance checks with the real gh (skipped locally when gh is missing or older than 2.102.0)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("network")
    }
    launcherTestProperties()
}

// This repo's own docs/ is an OKF bundle (okf.yaml), checked by the code this build compiles — not by a
// released jar, so CI needs no previous release, and a change to the checks is applied to these docs in the
// same build (docs/build-release.md). Each task runs `okf <command> --repo=<root>` + one `--<option>=<value>`
// per `-P<property>` that is set (an empty value is passed as such: `-Pokf.base=` → `--base=` = skip the
// impact check). CI runs `okfCheck okfImpact` as its own job (.github/workflows/build.yml); not dependencies
// of `check`/`build`.
fun registerOkfTask(name: String, command: String, summary: String, options: Map<String, String> = emptyMap()) =
    tasks.register<JavaExec>(name) {
        group = "documentation"
        description = summary
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(cliMainClass)
        val repoDir = layout.projectDirectory.asFile
        workingDir = repoDir
        args(command, "--repo=${repoDir.absolutePath}")
        val properties = options.map { (property, option) -> option to providers.gradleProperty(property) }
        argumentProviders.add(
            CommandLineArgumentProvider { properties.mapNotNull { (option, value) -> value.orNull?.let { "$option=$it" } } },
        )
    }

registerOkfTask("okfIndex", "index", "Regenerates the docs/ OKF bundle's generated sub-indexes from frontmatter.")
registerOkfTask(
    "okfCheck",
    "check",
    "Validates the docs/ OKF bundle: frontmatter, generated indexes, code references.",
    mapOf(
        "okf.strict" to "--strict",
        "okf.refs.strict" to "--refs-strict",
        "okf.codePaths.required" to "--code-paths-required",
    ),
).configure {
    // okfCheck reads the index files okfIndex writes; order them when both run in one invocation.
    mustRunAfter("okfIndex")
}
registerOkfTask(
    "okfImpact",
    "impact",
    "Reports docs whose code_paths match code changed since the merge-base, but that were neither updated nor acknowledged.",
    mapOf("okf.base" to "--base", "okf.impact.strict" to "--impact-strict"),
).configure {
    mustRunAfter("okfCheck")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "okf-cli"
            version = project.version.toString()
            // Thin jar + sources jar + POM (runtime deps: Kotlin stdlib with JetBrains Annotations, SnakeYAML) ...
            from(components["java"])
            // ... plus the fat CLI jar under the `all` classifier.
            artifact(allJar)

            pom {
                name.set("okf-cli")
                description.set(project.description)
                url.set("https://github.com/sharpbitstudio/okf-cli")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("sharpbitstudio")
                        name.set("SharpBit Studio")
                        organization.set("SharpBit Studio")
                        organizationUrl.set("https://github.com/sharpbitstudio")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/sharpbitstudio/okf-cli.git")
                    developerConnection.set("scm:git:ssh://git@github.com/sharpbitstudio/okf-cli.git")
                    url.set("https://github.com/sharpbitstudio/okf-cli")
                }
            }
        }
    }
}
