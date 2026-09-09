import io.moderne.build.Definalize
import java.util.concurrent.Callable
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    id("moderne-cli.java-conventions")
}

// Classpath for generateBundledJdkTypeTables: the project's main classes (which
// hold JdkTypeTableBuilder / JarTypeTableBuilder + ASM) plus rewrite-java for the
// JavaType model those builders produce. rewrite-java is compileOnly for the
// production jar, so the generator needs it supplied here.
val bundledJdkGeneratorRuntime by configurations.creating

dependencies {
    bundledJdkGeneratorRuntime(platform(libs.rewrite.bom))
    bundledJdkGeneratorRuntime("org.openrewrite:rewrite-java")

    compileOnly(platform(libs.rewrite.bom))
    compileOnly("org.openrewrite:rewrite-core")
    compileOnly("org.openrewrite:rewrite-java")
    compileOnly("org.openrewrite:rewrite-gradle")
    compileOnly("org.openrewrite:rewrite-groovy")
    compileOnly("org.openrewrite:rewrite-maven")
    compileOnly("org.openrewrite:rewrite-hcl")
    compileOnly("org.openrewrite:rewrite-json")
    compileOnly("org.openrewrite:rewrite-properties")
    compileOnly("org.openrewrite:rewrite-protobuf")
    compileOnly("org.openrewrite:rewrite-xml")
    compileOnly("org.openrewrite:rewrite-yaml")
    compileOnly("org.openrewrite:rewrite-kotlin")
    compileOnly("org.openrewrite:rewrite-javascript")
    compileOnly("org.openrewrite:rewrite-python")
    compileOnly("org.openrewrite:rewrite-docker")
    compileOnly("org.openrewrite:rewrite-toml")
    compileOnly("org.openrewrite:rewrite-mainframe:latest.release") // not BOM-covered
    compileOnly("org.openrewrite:rewrite-csharp")
    compileOnly("org.openrewrite:rewrite-go")
    compileOnly("org.openrewrite:rewrite-ruby")
    compileOnly("org.openrewrite:rewrite-scala")
    compileOnly("org.scala-lang:scala3-library_3:latest.release")

    implementation("org.ow2.asm:asm:9.9.1")

    implementation("com.univocity:univocity-parsers:latest.release")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.eatthepath:fast-uuid:latest.release")
    implementation("org.slf4j:slf4j-api:1.7.+")

    api("org.apache.commons:commons-compress:latest.release")

    implementation("org.apache.commons:commons-lang3:latest.release")
    implementation("org.tukaani:xz:latest.release")
    implementation("com.github.luben:zstd-jni:latest.release")
    implementation("at.yawk.lz4:lz4-java:latest.release")

    implementation(project(":core:core"))
    implementation(project(":core:search"))
    implementation(libs.rewrite.polyglot)
    implementation(libs.moderne.organizations.format)
    implementation("io.micrometer:micrometer-core:1.9.+")

    // From merged core:parsing — style autodetection and weight calculation
    compileOnly("org.openrewrite:rewrite-docker")
    compileOnly("org.openrewrite:rewrite-gradle")
    compileOnly("org.openrewrite:rewrite-groovy")
    compileOnly("org.openrewrite:rewrite-hcl")
    compileOnly("org.openrewrite:rewrite-json")
    compileOnly("org.openrewrite:rewrite-kotlin")
    compileOnly(libs.rewrite.scala)
    compileOnly(libs.rewrite.csharp)
    compileOnly("org.openrewrite:rewrite-xml")
    compileOnly("org.openrewrite:rewrite-yaml")
    compileOnly("org.openrewrite:rewrite-properties")
    compileOnly("org.openrewrite:rewrite-protobuf")
    compileOnly("org.openrewrite:rewrite-toml")

    testImplementation(platform(libs.rewrite.bom))
    testRuntimeOnly("org.openrewrite:rewrite-java-8")
    testRuntimeOnly("org.openrewrite:rewrite-java-11")
    testRuntimeOnly("org.openrewrite:rewrite-java-17")
    testRuntimeOnly("org.openrewrite:rewrite-java-21")
    testRuntimeOnly("org.openrewrite:rewrite-java-25")
    testRuntimeOnly(gradleApi())
    testImplementation("org.openrewrite:rewrite-gradle")
    testImplementation("org.openrewrite:rewrite-groovy")
    testImplementation("org.openrewrite:rewrite-xml")
    testImplementation("org.openrewrite:rewrite-test")
    testImplementation("org.openrewrite:rewrite-kotlin")
    testImplementation("org.openrewrite:rewrite-javascript")
    testImplementation("org.openrewrite:rewrite-python")
    testRuntimeOnly("org.openrewrite:rewrite-hcl")
    testImplementation("org.openrewrite:rewrite-maven")
    // Compile-scoped so the id-synthesis size measurement test can parse JSON
    // directly (JsonParser) — JSON is the data-heavy worst case for per-node
    // UUID overhead, the case IdPolicy.SYNTHESIZE targets.
    testImplementation("org.openrewrite:rewrite-json")
    testRuntimeOnly("org.openrewrite:rewrite-properties")
    testRuntimeOnly("org.openrewrite:rewrite-yaml")
    testRuntimeOnly("org.openrewrite:rewrite-csharp")
    testRuntimeOnly("org.openrewrite:rewrite-go")
    testRuntimeOnly("org.openrewrite:rewrite-ruby")
    testRuntimeOnly(libs.rewrite.scala)
    // Compile-scoped (not just runtime) so TreeSerializationTest can construct
    // Docker.Port directly — it is the only Tree node with a boxed @Nullable
    // Integer field, the regression case for nullable boxed-primitive codegen.
    testImplementation("org.openrewrite:rewrite-docker")
    testRuntimeOnly("org.openrewrite:rewrite-toml")
    testRuntimeOnly("org.openrewrite:rewrite-protobuf")
    // Compile-scoped (not just runtime) so the serialization coverage test can parse a
    // COBOL sample: COBOL is the only language whose composites nest composites.
    testImplementation("org.openrewrite:rewrite-mainframe:latest.release") // not BOM-covered

    constraints {
        testImplementation("com.google.guava:guava:32.1.1-jre") {
            because("CVE-2023-2976 and rewrite-python bringing in older version")
        }
    }

    testCompileOnly("org.projectlombok:lombok:latest.release")
    testAnnotationProcessor("org.projectlombok:lombok:latest.release")

    // Sizes the object graph a V2 read retains, so ReusableDeserializationContextTest can
    // measure the object-id bookkeeping separately from the LST that bookkeeping pins.
    testImplementation("org.openjdk.jol:jol-core:0.17")
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// ---- Strip ACC_FINAL from rewrite Tree node classes ----
// Allows generating lazy subclasses for zero-copy deserialization. Implementation
// in {@link io.moderne.build.Definalize} so mod's fat-jar packaging can reuse it.
val definalDir = layout.buildDirectory.dir("definalized-deps").get().asFile

val definalizeRewriteJars by tasks.registering {
    description = "Strips ACC_FINAL from rewrite Tree node classes for lazy subclassing"
    // Declare the resolved rewrite jars as inputs so a version flip
    // (latest.release → latest.integration, 8.83.6 → 8.83.7, fresh snapshot)
    // invalidates the staged definalized copies. Without this, the task is
    // considered up-to-date solely on the existence of the output dir and
    // stale class files load against the new rewrite jars with errors like
    // "cannot inherit from final J.CompilationUnit". Filter via artifactView
    // to external (org.openrewrite) module components only — declaring the
    // raw configurations as inputs would pull in this project's own jar and
    // create a cycle (compileJava → definalizeRewriteJars → jar → compileJava).
    val rewriteCompileJars = configurations.compileClasspath.get().incoming
        .artifactView {
            lenient(true)
            componentFilter { id ->
                // External rewrite modules (normal package build) AND, when rewrite is
                // source-linked via a composite build (includeBuild), the rewrite-* project
                // components — otherwise core:serialization can't subclass the Lombok-final
                // tree/marker nodes because the source-linked jars never get definalized.
                (id is ModuleComponentIdentifier && id.group == "org.openrewrite") ||
                        (id is ProjectComponentIdentifier && id.projectName.startsWith("rewrite-"))
            }
        }.files
    val rewriteTestJars = configurations.testRuntimeClasspath.get().incoming
        .artifactView {
            lenient(true)
            componentFilter { id ->
                // External rewrite modules (normal package build) AND, when rewrite is
                // source-linked via a composite build (includeBuild), the rewrite-* project
                // components — otherwise core:serialization can't subclass the Lombok-final
                // tree/marker nodes because the source-linked jars never get definalized.
                (id is ModuleComponentIdentifier && id.group == "org.openrewrite") ||
                        (id is ProjectComponentIdentifier && id.projectName.startsWith("rewrite-"))
            }
        }.files
    inputs.files(rewriteCompileJars, rewriteTestJars).withPropertyName("rewriteJars")
    // Local copies so the doLast action closes over plain File / FileCollection
    // values rather than the build script (`definalDir`, `configurations`), which
    // the configuration cache cannot serialize. The artifactViews already filter to
    // org.openrewrite modules, so iterating their files is equivalent to the old
    // resolvedArtifacts group filter — without resolving configurations at execution.
    val outDir = definalDir
    val sourceJars = rewriteCompileJars + rewriteTestJars
    outputs.dir(outDir)
    doLast {
        outDir.deleteRecursively()
        outDir.mkdirs()
        val seen = mutableSetOf<String>()
        sourceJars.files
            .filter { it.extension == "jar" }
            .forEach { jar ->
                if (seen.add(jar.absolutePath)) {
                    Definalize.definalizeJar(jar, File(outDir, jar.name))
                    logger.lifecycle("Definalized ${jar.name}")
                }
            }
    }
}

// Swap each rewrite jar on a classpath for its definalized copy once
// definalizeRewriteJars has produced it. The swap must happen at execution: some
// tasks (e.g. compileCodegenJava) don't have their classpath wired until after this
// block runs, and the definalized files don't exist until the task runs. The
// original doFirst referenced the top-level `definalDir` and `files()`, which
// captured the build script and broke the configuration cache. Instead we pre-create
// an empty file collection at configuration time and close over only it plus `dir`
// (a File) — the action reads/writes the task's own classpath and touches nothing
// from the script, so it serializes cleanly.
tasks.withType<JavaCompile>().configureEach {
    dependsOn(definalizeRewriteJars)
    val dir = definalDir
    val swapped = objects.fileCollection()
    doFirst {
        swapped.setFrom(classpath.files.map { f ->
            val byName = File(dir, f.name)
            if (byName.exists()) {
                // External module jar: definalized copy has the identical file name.
                byName
            } else if (f.isDirectory) {
                // Source-linked (composite) rewrite modules land on the classpath as class
                // dirs (.../rewrite-xxx/build/classes/java/main), not jars, so the name-based
                // match above misses them. Map the dir back to the definalized jar staged for
                // that module, or its tree/marker nodes stay Lombok-final and can't be subclassed.
                val mod = generateSequence(f) { it.parentFile }.map { it.name }
                    .firstOrNull { it.startsWith("rewrite-") }
                mod?.let { m ->
                    dir.listFiles()
                        ?.filter { it.isFile && it.extension == "jar" && it.name.startsWith("$m-") }
                        ?.minByOrNull { it.name.length }
                } ?: f
            } else {
                f
            }
        })
        classpath = swapped
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(definalizeRewriteJars)
    val dir = definalDir
    val swapped = objects.fileCollection()
    doFirst {
        swapped.setFrom(classpath.files.map { f ->
            val byName = File(dir, f.name)
            if (byName.exists()) {
                // External module jar: definalized copy has the identical file name.
                byName
            } else if (f.isDirectory) {
                // Source-linked (composite) rewrite modules land on the classpath as class
                // dirs (.../rewrite-xxx/build/classes/java/main), not jars, so the name-based
                // match above misses them. Map the dir back to the definalized jar staged for
                // that module, or its tree/marker nodes stay Lombok-final and can't be subclassed.
                val mod = generateSequence(f) { it.parentFile }.map { it.name }
                    .firstOrNull { it.startsWith("rewrite-") }
                mod?.let { m ->
                    dir.listFiles()
                        ?.filter { it.isFile && it.extension == "jar" && it.name.startsWith("$m-") }
                        ?.minByOrNull { it.name.length }
                } ?: f
            } else {
                f
            }
        })
        classpath = swapped
    }
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(definalizeRewriteJars)
    val dir = definalDir
    val swapped = objects.fileCollection()
    doFirst {
        swapped.setFrom(classpath.files.map { f ->
            val byName = File(dir, f.name)
            if (byName.exists()) {
                // External module jar: definalized copy has the identical file name.
                byName
            } else if (f.isDirectory) {
                // Source-linked (composite) rewrite modules land on the classpath as class
                // dirs (.../rewrite-xxx/build/classes/java/main), not jars, so the name-based
                // match above misses them. Map the dir back to the definalized jar staged for
                // that module, or its tree/marker nodes stay Lombok-final and can't be subclassed.
                val mod = generateSequence(f) { it.parentFile }.map { it.name }
                    .firstOrNull { it.startsWith("rewrite-") }
                mod?.let { m ->
                    dir.listFiles()
                        ?.filter { it.isFile && it.extension == "jar" && it.name.startsWith("$m-") }
                        ?.minByOrNull { it.name.length }
                } ?: f
            } else {
                f
            }
        })
        classpath = swapped
    }
}

// ---- V3 tree serialization codegen ----
sourceSets {
    create("codegen") {
        java.srcDir("src/codegen/java")
        compileClasspath += sourceSets.main.get().compileClasspath
        runtimeClasspath += sourceSets.main.get().compileClasspath
    }
    // Let tests exercise the (pure) codegen helpers — e.g. the tag registry —
    // directly. Attach the codegen output to the test source set's classpath
    // rather than via testImplementation: definalizeRewriteJars declares the
    // testRuntimeClasspath *configuration* as an input, so routing codegen
    // output through that configuration would create a cycle
    // (definalizeRewriteJars -> codegenClasses -> compileCodegenJava ->
    // definalizeRewriteJars, the last edge via the JavaCompile configureEach).
    named("test") {
        compileClasspath += sourceSets["codegen"].output
        runtimeClasspath += sourceSets["codegen"].output
    }
}

val generatedV3Dir = layout.buildDirectory.dir("generated/v3").get().asFile

// Committed, generator-only registry of stable wire tags (per Tree type) and
// field ids. The generator reads it, validates present types, append-allocates
// tags/ids for new ones, and rewrites it when (and only when) something changed
// — so a new Tree type or field shows up as a reviewable diff to commit. Pass
// -PforceV3Codegen to tombstone-and-reallocate on a wire-incompatible change.
val v3TagRegistry = layout.projectDirectory.file("src/codegen/resources/tree-registry.json")

val generateV3TreeCode by tasks.registering(JavaExec::class) {
    description = "Generate V3 tree serialization code from Tree class introspection"
    dependsOn("compileCodegenJava", definalizeRewriteJars)
    classpath = sourceSets["codegen"].runtimeClasspath
    inputs.file(v3TagRegistry).withPropertyName("tagRegistry")
    if (project.hasProperty("forceV3Codegen")) {
        systemProperty("v3codegen.force", "true")
    }
    // Local copy so the doFirst closes over a File, not the build script. The
    // definalized-classpath swap is now applied at configuration time by the
    // withType<JavaExec> block above, so the action only wipes prior output.
    val genDir = generatedV3Dir
    doFirst {
        // Wipe prior output so files the codegen no longer emits (e.g. after a
        // design change) don't linger and get compiled against deleted symbols.
        genDir.deleteRecursively()
    }
    mainClass.set("io.moderne.serialization.v3.codegen.TreeCodegen")
    args(generatedV3Dir.absolutePath, v3TagRegistry.asFile.absolutePath)
    outputs.dir(generatedV3Dir)
}

sourceSets.main {
    java.srcDir(generateV3TreeCode.map { generatedV3Dir })
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateV3TreeCode)
}

// ---- Bundled LTS JDK type tables ----
//
// Pre-build the JDK module type tables for each LTS at CLI build time and ship
// them inside the jar under io/moderne/serialization/v3/type/bundled-jdk/. At
// runtime BundledJdkTypeTables materializes them into the on-disk type cache, so
// a build box without the source set's JDK installed (and without jmods/) can
// still resolve LTS JDK types — for the running JVM and for repos compiled
// against a different LTS than the one running the CLI. See DependencyTypeTableStore.
//
// Foojay (settings.gradle.kts) auto-fetches LTS JDKs Gradle doesn't see locally;
// machines that already have an LTS JDK installed reuse it.
val ltsJdks = listOf(8, 11, 17, 21, 25)
val bundledJdkResourcesRoot = layout.buildDirectory.dir("generated-resources/bundled-jdk")
val bundledJdkOutputDir = bundledJdkResourcesRoot.map {
    it.dir("io/moderne/serialization/v3/type/bundled-jdk")
}

// Resolve each LTS launcher at config time so toolchain auto-download begins as
// soon as Gradle inspects this task — but the .get() that actually requests the
// JDK is deferred to doFirst, so a clean configure phase forces no download.
val ltsLaunchers = ltsJdks.associateWith { v ->
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(v)) }
}

val generateBundledJdkTypeTables by tasks.registering(JavaExec::class) {
    description = "Generates bundled-jdk/<module>-N.bin + index-N.txt resources for each LTS"
    group = "build"
    dependsOn("classes")

    // Local copies so the input Callable, doFirst, and onlyIf below close over the
    // launcher map / output dir provider rather than the top-level vals (which would
    // capture the build script and break the configuration cache).
    val launchers = ltsLaunchers
    val outDirProvider = bundledJdkOutputDir

    classpath = sourceSets.main.get().runtimeClasspath +
            sourceSets.main.get().output +
            bundledJdkGeneratorRuntime
    mainClass.set("io.moderne.serialization.v3.type.BundledJdkTypeTableGenerator")

    inputs.property("ltsJdks", ltsJdks)
    // Invalidate when a provisioned JDK actually changes (e.g. an in-place patch
    // upgrade), not just when the LTS list changes — otherwise stale .bin tables
    // ship silently. Lazy (Callable) so it's snapshotted at execution time, only
    // when this task is in the graph (a jar build), which already resolves these
    // toolchains via onlyIf below; test-only builds never trigger it.
    inputs.property("jdkRuntimeVersions", Callable {
        launchers.values.mapNotNull {
            runCatching { it.get().metadata.javaRuntimeVersion }.getOrNull()
        }.sorted()
    })
    outputs.dir(bundledJdkResourcesRoot)
    // The generated tables are a pure function of the declared inputs (LTS list +
    // each provisioned JDK's runtime version), so the output is safe to share via
    // the build cache. JavaExec isn't cacheable by default; opt in so a fresh
    // worktree pulls these tables (~23s to regenerate) from the local cache instead.
    outputs.cacheIf { true }

    doFirst {
        val outDir = outDirProvider.get().asFile
        outDir.mkdirs()
        // Args: <outputDir> <jdkHome>... — soft-fail per LTS so a build that
        // can't provision one JDK (offline, no Foojay) still ships the others.
        val argList = mutableListOf(outDir.absolutePath)
        launchers.forEach { (v, launcher) ->
            try {
                argList.add(launcher.get().metadata.installationPath.asFile.absolutePath)
            } catch (e: Exception) {
                logger.warn("[bundled-jdk] skipping jdk-$v (toolchain unavailable: ${e.message})")
            }
        }
        args = argList
    }

    // No LTS toolchain resolvable at all (offline, none installed): skip and ship
    // without bundled tables — the runtime jmods path / graceful skip still apply.
    onlyIf {
        launchers.any { (_, launcher) ->
            try { launcher.get(); true } catch (e: Exception) { false }
        }
    }
}

// Include the generated resources directly in the jar (the directory is the
// classpath root, so files land at io/moderne/serialization/v3/type/bundled-jdk/).
// Deliberately NOT wired into the test classpath: generating five LTS tables is
// expensive, and the runtime path is covered by a synthetic fixture in
// BundledJdkTypeTablesTest.
tasks.named<Jar>("jar") {
    dependsOn(generateBundledJdkTypeTables)
    from(bundledJdkResourcesRoot)
}

val jvmTestArgs = listOf(
    "-XX:+UnlockDiagnosticVMOptions", "-XX:+ShowHiddenFrames",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
    "--add-opens", "jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
    // String.value/String.coder VarHandles for transcode-free string writes
    // (BinaryOutput.writeString); mirrors the modw wrapper's runtime flag.
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED"
)

tasks.named<Test>("test").configure {
    useJUnitPlatform {
        excludeTags("debug")
    }
    jvmArgs = jvmTestArgs
    maxHeapSize = "1g"
}

tasks.register<Test>("jmh") {
    description = "Run JMH benchmarks"
    group = "verification"
    useJUnitPlatform {
        includeTags("debug")
    }
    // -PjmhJvmArgs="..." appends space-separated flags, so a benchmark can be run across
    // GC / heap-layout configurations without editing this file.
    jvmArgs = jvmTestArgs + (findProperty("jmhJvmArgs") as String? ?: "")
        .split(" ").filter { it.isNotBlank() }
    maxHeapSize = "2g"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
}
