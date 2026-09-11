// Local-only, uncommitted source linking of the OpenRewrite SDK, plus the rest of the
// per-session setup that used to be a checklist of hand-run commands.
//
// Applied with:
//
//   ./gradlew :mod:devFatJar :devRecipesRegister -I .local/gradle/local-rewrite.init.gradle.kts \
//       -PrewriteWorktree=<abs path to the injected rewrite worktree> \
//       -PrecipesWorktree=<abs path to the injected recipes-csharp worktree>
//
// Given those two paths — the only inputs that are not derivable from disk, because they name
// sibling Conductor worktrees — this script owns the whole source-linked setup.
//
// Unconditionally, before the build configures:
//
//   * points external/openrewrite/rewrite in BOTH the CLI worktree and the recipes worktree at
//     the rewrite worktree, creating or repointing the symlinks as needed;
//   * builds the three rewrite jars the CLI opens during its own configuration;
//   * includes rewrite as a composite build, so the fat jar compiles the SDK from source.
//
// Then two tasks, neither of which depends on the other:
//
//   :devCsharpRecipesBuild   builds the C# recipes solution against the source-linked SDK.
//   :devRecipesRegister      rebuilds $MODERNE_CLI_HOME/recipes-v5.csv from scratch out of the
//                            local artifacts — the pTML'd rewrite-core/rewrite-java jars and the
//                            built recipe assemblies — reusing a cached registration for any
//                            artifact byte-identical to one registered before.
//
// :devRecipesRegister is deliberately NOT wired into :mod:devFatJar. Its cost is a function of
// how many artifacts changed, and a recipe *source* change always changes the assembly, so
// coupling it to the jar would make every fat-jar build pay for a re-registration that only
// matters when recipe DECLARATIONS move. Run it on first setup and whenever recipes are added,
// renamed, or have their options/descriptions changed. When both are asked for, ordering is
// arranged automatically.
//
// Omit -PrecipesWorktree and neither task is registered; the source-linking above still happens.
//
// Properties:
//   -PrewriteWorktree=<path>            rewrite (SDK) worktree; without it the script falls
//                                       back to whatever symlink already exists
//   -PrecipesWorktree=<path>            recipes-csharp worktree; enables the two tasks
//   -PrecipeConfiguration=Debug         build configuration for the recipe assemblies
//   -PdeterministicRecipeBuild=false    opt out of reproducible recipe builds (see below)
//   -PforceRecipeRegistration=true      ignore the registration cache and re-register
//
// NOTE on composite-build scope: we intentionally do NOT add an explicit
// `dependencySubstitution` block. rewrite supplies its subproject plugin versions from its
// ROOT project (org.openrewrite.build.root), and scoped/explicit substitution makes Gradle
// configure the substituted subprojects before that root contributes the plugin classpath ->
// "plugin ... must include a version number". Auto-substitution configures the included build
// holistically (root first), which resolves cleanly. Consequence: every org.openrewrite module
// the CLI depends on is sourced from the rewrite checkout (rewrite-core/java/csharp plus the
// rest). rewrite's own build cache (ge.openrewrite.org) serves most compile outputs, so this
// stays fast.
//
// Nothing here is committed; settings.gradle.kts and libs.versions.toml are untouched.

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// ===========================================================================================
// Marketplace CSV
//
// $MODERNE_CLI_HOME/recipes-v5.csv is written by rewrite's RecipeMarketplaceWriter (univocity,
// default dialect) and read back by RecipeMarketplaceReader. Two properties of that file rule
// out line-oriented text editing, which is what this section exists to replace:
//
//   * descriptions carry embedded commas, quotes and NEWLINES, so a record spans an
//     unpredictable number of lines;
//   * the header is DYNAMIC — requestedVersion/version/options/dataTables/team/categoryN are
//     emitted only when some recipe in the file needs them — so two CLI homes holding
//     different packages have different column sets, and column position means nothing.
//
// So: a small RFC 4180 reader/writer, and rows modelled as name -> value maps throughout.
// The reader on the other side is header-name driven and order-independent, which is what
// makes splicing rows in from elsewhere sound.
// ===========================================================================================

fun parseCsv(text: String): List<List<String>> {
    val records = mutableListOf<List<String>>()
    var record = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0

    fun endRecord() {
        record.add(field.toString())
        field.setLength(0)
        // A trailing newline yields a single empty field; that is the end of the file, not a row.
        if (record.size > 1 || record[0].isNotEmpty()) {
            records.add(record)
        }
        record = mutableListOf()
    }

    while (index < text.length) {
        val c = text[index]
        when {
            quoted -> when {
                c != '"' -> field.append(c)
                // A doubled quote inside a quoted field is a literal quote.
                index + 1 < text.length && text[index + 1] == '"' -> {
                    field.append('"')
                    index++
                }
                else -> quoted = false
            }
            c == '"' -> quoted = true
            c == ',' -> {
                record.add(field.toString())
                field.setLength(0)
            }
            c == '\r' -> Unit // CRLF: the '\n' ends the record
            c == '\n' -> endRecord()
            else -> field.append(c)
        }
        index++
    }
    if (field.isNotEmpty() || record.isNotEmpty()) {
        endRecord()
    }
    return records
}

fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/** Column order as RecipeMarketplaceWriter would emit it. */
fun columnRank(name: String): Pair<Int, Int> {
    val fixed = listOf("ecosystem", "packageName", "requestedVersion", "version",
            "name", "displayName", "description", "recipeCount").indexOf(name)
    fun suffix(text: String) = text.toIntOrNull() ?: 0
    return when {
        fixed >= 0 -> fixed to 0
        name.startsWith("category") && name.endsWith("Description") ->
            9 to suffix(name.removePrefix("category").removeSuffix("Description"))
        name.startsWith("category") -> 8 to suffix(name.removePrefix("category"))
        name == "team" -> 11 to 0
        name == "options" -> 12 to 0
        name == "dataTables" -> 13 to 0
        else -> 10 to 0 // unknown columns are read back as recipe metadata
    }
}

fun readMarketplace(file: File): List<Map<String, String>> {
    if (!file.isFile) {
        return emptyList()
    }
    val records = parseCsv(file.readText())
    if (records.isEmpty()) {
        return emptyList()
    }
    val header = records[0].map { it.trim() }
    return records.drop(1).map { cells ->
        header.withIndex().associate { (i, name) -> name to (cells.getOrNull(i) ?: "") }
    }
}

/**
 * Write [rows] with a header covering every column any row uses, replacing [file] atomically.
 *
 * Sorting rows and ordering columns is cosmetic — the reader does not care — but it keeps this
 * file recognisable as the same file the CLI writes, so the CLI's next install produces a small
 * diff rather than a wholesale reshuffle.
 */
fun writeMarketplace(file: File, rows: List<Map<String, String>>) {
    val columns = rows.flatMap { it.keys }.distinct()
            .sortedWith(compareBy({ columnRank(it).first }, { columnRank(it).second }, { it }))
    val text = buildString {
        append(columns.joinToString(",", postfix = "\n") { csvField(it) })
        rows.sortedBy { row -> columns.joinToString(" ") { row[it].orEmpty() } }
                .forEach { row ->
                    append(columns.joinToString(",", postfix = "\n") { csvField(row[it].orEmpty()) })
                }
    }
    file.parentFile.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(text)
    Files.move(temp.toPath(), file.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

// ===========================================================================================
// Recipe registration cache
//
// `mod config recipes <jar|nuget> install ...` loads every recipe in an artifact through the
// language RPC server just to read its shape (name, description, options, data tables), then
// writes the result into recipes-v5.csv. It is the slowest part of standing up a worktree —
// ~20s per artifact here — and it has to happen again in every fresh worktree, even though the
// answer is a pure function of the artifact.
//
// So key the answer on the SHA-256 of the artifact and keep it in a global cache at
//
//     ~/.moderne/recipe-cache/<identity>/<sha256>/recipes-v5.csv
//
// and treat a hit as a fast path around the CLI: splice the rows straight into the catalogue
// instead of loading the artifact at all.
//
// Both artifact kinds are built reproducibly, which is what makes the key work across worktrees
// and commits:
//
//   .NET assemblies  the MSBuild determinism flags on devCsharpRecipesBuild
//   rewrite jars     .local/gradle/reproducible-info.init.gradle.kts, passed to the nested pTML
//
// A cache entry holds only that artifact's rows, with the columns that name *where* the artifact
// lives replaced by placeholders — the absolute DLL path for nuget, the version for maven. So
// restoring is "substitute this worktree's values back in and splice".
// ===========================================================================================

val PLACEHOLDER = "{ARTIFACT}"

// Retained per identity; entries are small, but a long-lived machine would otherwise accumulate
// one directory per distinct build forever.
val MAX_CACHE_ENTRIES_PER_ARTIFACT = 20

// Recipe assemblies live next to their test/harness siblings, which contain no recipes.
val NON_RECIPE_SUFFIXES = listOf(".Tests", ".Test", ".Harness")

// rewrite modules that carry recipes and must always be in the catalogue. The CLI bundles their
// classes in the fat jar, but a recipe is only runnable once it is in the marketplace.
val BASE_REWRITE_MODULES = listOf("rewrite-core", "rewrite-java")

/**
 * One artifact to be registered.
 *
 * [identity]  cache directory name; stable across worktrees and versions
 * [file]      what gets hashed
 * [ecosystem] the `mod config recipes <ecosystem> install` family, and the CSV column value
 * [coordinate] what to pass to that install command, and what `packageName` holds afterwards
 * [volatile]  columns whose values name this worktree/build rather than the recipe, and so must
 *             be re-stamped on restore instead of being taken from the cache
 */
class Artifact(
    val identity: String,
    val file: File,
    val ecosystem: String,
    val coordinate: String,
    val volatileColumns: Map<String, String>,
)

fun dotnetArtifact(name: String, dll: File) = Artifact(
    identity = name,
    file = dll,
    ecosystem = "nuget",
    coordinate = dll.path,
    // packageName IS the absolute DLL path, so it points at whichever worktree registered last.
    volatileColumns = mapOf("packageName" to dll.path),
)

fun mavenArtifact(module: String, jar: File, version: String) = Artifact(
    identity = "org.openrewrite--$module",
    file = jar,
    ecosystem = "jar",
    coordinate = "org.openrewrite:$module:$version",
    // The GA coordinate is stable; the version moves with every nebula-inferred bump.
    volatileColumns = mapOf("requestedVersion" to version, "version" to version),
)

/** How rows for this artifact are recognised in the catalogue the CLI just wrote. */
fun Artifact.ownsRow(row: Map<String, String>): Boolean {
    val ecosystemColumn = if (ecosystem == "jar") "maven" else ecosystem
    if (!row["ecosystem"].orEmpty().equals(ecosystemColumn, ignoreCase = true)) {
        return false
    }
    // maven rows carry the GA coordinate in packageName; nuget-from-DLL rows carry the path.
    val expected = if (ecosystem == "jar") coordinate.substringBeforeLast(':') else coordinate
    return row["packageName"] == expected
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** (assembly name, DLL) for every recipe project in [recipesRoot] with a built output. */
fun discoverRecipeAssemblies(recipesRoot: File, configuration: String,
                             targetFramework: String): List<Pair<String, File>> =
    (recipesRoot.listFiles() ?: emptyArray())
        .filter { it.isDirectory }
        .map { it.name }
        .filter { it.startsWith("OpenRewrite.Recipes.") && NON_RECIPE_SUFFIXES.none(it::endsWith) }
        .sorted()
        .mapNotNull { name ->
            val dll = File(recipesRoot, "$name/bin/$configuration/$targetFramework/$name.dll")
            if (dll.isFile) name to dll.canonicalFile else null
        }

fun cacheEntry(cacheRoot: File, artifact: Artifact, digest: String) =
    File(cacheRoot, "${artifact.identity}/$digest")

fun loadCachedRows(cacheRoot: File, artifact: Artifact, digest: String): List<Map<String, String>>? {
    val entry = cacheEntry(cacheRoot, artifact, digest)
    val rows = readMarketplace(File(entry, "recipes-v5.csv"))
    if (rows.isEmpty()) {
        return null
    }
    entry.setLastModified(System.currentTimeMillis()) // keep the pruner honest about live entries
    return rows.map { it + artifact.volatileColumns }
}

fun storeCachedRows(cacheRoot: File, artifact: Artifact, digest: String,
                    rows: List<Map<String, String>>) {
    val entry = cacheEntry(cacheRoot, artifact, digest)
    entry.mkdirs()
    val blanked = artifact.volatileColumns.mapValues { PLACEHOLDER }
    writeMarketplace(File(entry, "recipes-v5.csv"), rows.map { it + blanked })

    File(cacheRoot, artifact.identity).listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(MAX_CACHE_ENTRIES_PER_ARTIFACT)
        ?.forEach { it.deleteRecursively() }
}

// ===========================================================================================

// Only act on the top-level build; included builds (e.g. rewrite) must not re-enter.
if (gradle.parent == null) {

    val startProperties = gradle.startParameter.projectProperties

    fun initProperty(name: String): String? =
        startProperties[name]?.trim()?.takeIf { it.isNotEmpty() }

    fun initFlag(name: String, default: Boolean): Boolean =
        initProperty(name)?.toBoolean() ?: default

    val rewriteWorktree = initProperty("rewriteWorktree")?.let { File(it).absoluteFile }
    val recipesWorktree = initProperty("recipesWorktree")?.let { File(it).absoluteFile }
    val recipeConfiguration = initProperty("recipeConfiguration") ?: "Debug"

    for ((label, dir) in listOf("rewriteWorktree" to rewriteWorktree, "recipesWorktree" to recipesWorktree)) {
        if (dir != null && !dir.isDirectory) {
            throw GradleException("[local-rewrite] -P$label=$dir is not a directory")
        }
    }

    /**
     * Point [link] at [target], replacing an existing symlink that points elsewhere.
     * A real directory in the way is left alone and reported: that is someone's checkout,
     * not something this script put there.
     */
    fun repoint(link: Path, target: Path) {
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link) == target) {
                return
            }
            Files.delete(link)
        } else if (Files.exists(link)) {
            throw GradleException("[local-rewrite] $link already exists and is not a symlink; " +
                    "remove it to let the source link be created")
        }
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, target)
        println("[local-rewrite] linked $link -> $target")
    }

    fun sourceLink(worktree: File, rewrite: File) =
        repoint(worktree.toPath().resolve("external/openrewrite/rewrite"), rewrite.toPath())

    // mod/build.gradle.kts opens each of these modules' jars with ZipFile during
    // CONFIGURATION, to read the META-INF/rewrite-<lang>-version.txt that pins the language
    // engine the runtime fetches.
    val configurationTimeRewriteJars = listOf("rewrite-javascript", "rewrite-python", "rewrite-csharp")

    /**
     * Build the jars the CLI reads at configuration time.
     *
     * In composite-build mode those artifacts are the included build's build/libs jars, which a
     * freshly created rewrite worktree has not produced yet — so every task, devFatJar included,
     * dies during configuration with nothing but the missing jar's path as the message. Nothing
     * inside the CLI build can recover from it: the failure happens while its build script is
     * still being evaluated, long before any task could have built the jar.
     *
     * This runs unconditionally rather than only when the jars are absent. An existence check
     * cannot tell a usable jar from one left at a version nebula has since bumped past, and that
     * stale-jar case fails exactly like the missing-jar case. Gradle's own up-to-date checks make
     * the steady-state cost ~5s, which is worth paying to make the failure mode unreachable.
     */
    fun bootstrapRewriteJars(rewrite: File) {
        println("[local-rewrite] ensuring ${configurationTimeRewriteJars.joinToString()} are built " +
                "(the CLI build reads these jars during configuration)")
        val exit = ProcessBuilder(listOf(File(rewrite, "gradlew").absolutePath) +
                configurationTimeRewriteJars.map { ":$it:jar" })
            .directory(rewrite)
            .inheritIO()
            .start()
            .waitFor()
        if (exit != 0) {
            throw GradleException("[local-rewrite] could not build " +
                    "${configurationTimeRewriteJars.joinToString()} in $rewrite")
        }
    }

    beforeSettings {
        // Both links must exist before anything reads them, and the CLI's is read during
        // settings evaluation right below — so this cannot wait for a task.
        if (rewriteWorktree != null) {
            sourceLink(rootDir, rewriteWorktree)
            // The recipes worktree needs the same link for a different reason: its
            // Directory.Build.props flips UseLocalRewrite on the link's existence, and
            // Recipes.Source.slnx reaches the SDK project through it. Easily forgotten
            // when done by hand, because it lives in another worktree entirely.
            if (recipesWorktree != null) {
                sourceLink(recipesWorktree, rewriteWorktree)
            }
            bootstrapRewriteJars(rewriteWorktree)
        }

        val rewriteDir = rootDir.resolve("external/openrewrite/rewrite")
        if (rewriteDir.exists()) {
            println("[local-rewrite] source-linking OpenRewrite SDK from $rewriteDir")
            includeBuild(rewriteDir)
        } else {
            println("[local-rewrite] external/openrewrite/rewrite not found; using packages")
        }
    }

    // rewrite-java's PUBLISHED jar shades + relocates checkstyle
    // (com.puppycrawl.tools.checkstyle -> org.openrewrite.tools.checkstyle) and bundles it.
    // The source-linked classes are NOT shaded, so they still reference the original
    // com.puppycrawl checkstyle, which isn't otherwise on consumers' classpath. Provide it.
    allprojects {
        if (rootProject.projectDir.resolve("external/openrewrite/rewrite").exists()) {
            plugins.withId("java") {
                dependencies.add("implementation", "com.puppycrawl.tools:checkstyle:9.+")
                // Source-linked rewrite-core exposes univocity-parsers only as `implementation`,
                // so consumers like core/serialization (which import com.univocity.parsers.csv via
                // compileOnly rewrite-core) lose it at compile time. The published rewrite-core jar
                // exposes it on the compile classpath. Same gap as checkstyle above; runtime already
                // has it via the bundled rewrite-core, so compileOnly is sufficient.
                dependencies.add("compileOnly", "com.univocity:univocity-parsers:latest.release")
                if (path == ":core:serialization") {
                    // core/serialization compiles against org.openrewrite.mainframe.* and
                    // org.openrewrite.ruby.*. Current HEAD declares both, but a workspace whose
                    // skip-worktree overlay of core/serialization/build.gradle.kts predates the
                    // rewrite-cobol -> rewrite-mainframe rename asks for the old coordinate instead,
                    // whose last release (2.24.0) still carries the org.openrewrite.<lang> layout.
                    // Adding them here is a no-op when the build script already declares them, and
                    // saves an otherwise baffling "package org.openrewrite.mainframe.jcl does not
                    // exist" (plus a V3 codegen guard failure on Cobol$Word) when it does not.
                    // Scope matters: added to every project, rewrite-ruby makes :prebuild:gradle
                    // fail with a variant-ambiguity error against the composite build.
                    dependencies.add("compileOnly", "org.openrewrite:rewrite-mainframe:latest.release")
                    dependencies.add("compileOnly", "org.openrewrite:rewrite-ruby:latest.release")
                }
            }
        }
    }

    if (recipesWorktree != null) {
        val rpcServer = rewriteWorktree
            ?.resolve("rewrite-csharp/csharp/OpenRewrite.Tool/OpenRewrite.Tool.csproj")

        rootProject {
            // Honour whatever MODERNE_CLI_HOME the caller is running under, because that is the
            // home `mod` itself will read later: each Conductor worktree's settings.local.json
            // exports its OWN .local/.moderne/cli, so pinning this to the CLI worktree would
            // write the catalogue somewhere `mod` never looks whenever the primary workspace is
            // the rewrite or recipes worktree. Fall back to this worktree when nothing is set.
            val cliHome = (System.getenv("MODERNE_CLI_HOME")?.takeIf { it.isNotBlank() }
                ?.let { File(it) } ?: File(rootDir, ".local/.moderne/cli")).absoluteFile
            val cacheRoot = File(System.getProperty("user.home"), ".moderne/recipe-cache")
            val modw = File(rootDir, "modw")

            val buildCsharpRecipes = tasks.register("devCsharpRecipesBuild", Exec::class.java) {
                group = "local dev"
                description = "Build the linked C# recipes solution against the source-linked SDK"
                workingDir = recipesWorktree

                doFirst {
                    // Selected on the same signal the recipes repo's own build uses: with the
                    // symlink present, Directory.Build.props ProjectReferences the SDK source,
                    // and only Recipes.Source.slnx includes that project in the graph.
                    val sourceLinked = recipesWorktree.resolve("external/openrewrite/rewrite").exists()
                    val solution = if (sourceLinked) "Recipes.Source.slnx" else "Recipes.slnx"

                    commandLine(buildList {
                        add("dotnet")
                        add("build")
                        add(solution)
                        add("-c")
                        add(recipeConfiguration)
                        if (initFlag("deterministicRecipeBuild", true)) {
                            // Make the recipe assemblies byte-reproducible so the registration
                            // cache can be keyed on their content hash. Each of these removes a
                            // specific input that would otherwise vary between two builds of
                            // identical sources:
                            //
                            //   ContinuousIntegrationBuild            the absolute obj/ path of
                            //     the PDB, which is embedded in the assembly's debug directory
                            //     and so differs per worktree
                            //   IncludeSourceRevisionInInformationalVersion  the git SHA the SDK
                            //     appends to AssemblyInformationalVersion, which changes on
                            //     every commit
                            //   EnableSourceLink=false                the SourceLink document
                            //     (also SHA-bearing) written into the PDB, whose content ID the
                            //     assembly carries
                            //
                            // Verified: with all three, the same sources build to the same bytes
                            // from two different directories at two different commits.
                            //
                            // The cost is that PDB source paths become /_/-relative, so a
                            // debugger cannot find local sources from these outputs. IDE and
                            // `dotnet test` builds do not pass these flags and are unaffected;
                            // alternating between the two does force a recompile — harmless,
                            // because the flagged rebuild restores the deterministic bytes.
                            add("-p:ContinuousIntegrationBuild=true")
                            add("-p:IncludeSourceRevisionInInformationalVersion=false")
                            add("-p:EnableSourceLink=false")
                        }
                    })
                    logger.lifecycle("[local-rewrite] building recipes: ${commandLine.joinToString(" ")}")
                }
            }

            tasks.register("devRecipesRegister") {
                group = "local dev"
                description = "Rebuild \$MODERNE_CLI_HOME/recipes-v5.csv from the local artifacts (cached)"
                dependsOn(buildCsharpRecipes)
                // Ordering only, not a dependency: `./gradlew :devRecipesRegister` on its own must
                // not drag in a fat-jar build. When both are asked for, this goes last, because a
                // cache miss shells out to the jar it produces.
                mustRunAfter(":mod:devFatJar")

                doLast {
                    val force = initFlag("forceRecipeRegistration", false)

                    // --- collect what should be in the catalogue -------------------------------
                    val artifacts = mutableListOf<Artifact>()

                    if (rewriteWorktree != null) {
                        // pTML so the CLI can resolve these from ~/.m2. The nested build carries
                        // reproducible-info.init.gradle.kts, without which every publish produces
                        // a different jar (Build-Date) and nothing would ever cache.
                        logger.lifecycle("[local-rewrite] publishing ${BASE_REWRITE_MODULES.joinToString()} " +
                                "to ~/.m2")
                        val reproducible = File(rootDir, ".local/gradle/reproducible-info.init.gradle.kts")
                        val publish = ProcessBuilder(
                                listOf(File(rewriteWorktree, "gradlew").absolutePath) +
                                        BASE_REWRITE_MODULES.map { ":$it:publishToMavenLocal" } +
                                        listOf("-I", reproducible.absolutePath))
                            .directory(rewriteWorktree)
                            .inheritIO()
                            .start()
                        if (publish.waitFor() != 0) {
                            throw GradleException("[local-rewrite] could not publish " +
                                    "${BASE_REWRITE_MODULES.joinToString()} to ~/.m2")
                        }

                        for (module in BASE_REWRITE_MODULES) {
                            // Hash the build output rather than the ~/.m2 copy: same bytes, and it
                            // carries the version in its name, so no second lookup to find it.
                            val jar = File(rewriteWorktree, "$module/build/libs").listFiles()
                                ?.filter { it.name.startsWith("$module-") && it.name.endsWith(".jar") }
                                ?.singleOrNull { !it.name.contains("-sources") && !it.name.contains("-javadoc") }
                                ?: throw GradleException("[local-rewrite] no jar for $module in " +
                                        "$rewriteWorktree/$module/build/libs")
                            val version = jar.name.removePrefix("$module-").removeSuffix(".jar")
                            artifacts += mavenArtifact(module, jar, version)
                        }
                    }

                    val assemblies = discoverRecipeAssemblies(recipesWorktree, recipeConfiguration, "net10.0")
                    if (assemblies.isEmpty()) {
                        throw GradleException("[local-rewrite] no built recipe assemblies under " +
                                "$recipesWorktree — did the solution build?")
                    }
                    assemblies.forEach { (name, dll) -> artifacts += dotnetArtifact(name, dll) }

                    // --- rebuild the catalogue from nothing -----------------------------------
                    // Starting empty is what keeps this honest: rows for artifacts that moved,
                    // were deleted, or came from someone else's worktree cannot survive, so the
                    // catalogue always describes exactly the artifacts this session built.
                    val rows = mutableListOf<Map<String, String>>()
                    val misses = mutableListOf<Pair<Artifact, String>>()

                    for (artifact in artifacts) {
                        val digest = sha256(artifact.file)
                        val cached = if (force) null else loadCachedRows(cacheRoot, artifact, digest)
                        if (cached == null) {
                            misses += artifact to digest
                        } else {
                            rows += cached
                            logger.lifecycle("[local-rewrite] ${artifact.identity} — " +
                                    "${cached.size} recipes from cache")
                        }
                    }

                    val csv = File(cliHome, "recipes-v5.csv")
                    // Write the restored rows BEFORE installing: the CLI reads, merges and
                    // rewrites this file, so anything not on disk by then is lost.
                    writeMarketplace(csv, rows)

                    if (misses.isEmpty()) {
                        logger.lifecycle("[local-rewrite] catalogue rebuilt at $csv " +
                                "(${rows.size} recipes, entirely from cache)")
                        return@doLast
                    }

                    // --- register the misses through the CLI ----------------------------------
                    if (!modw.canExecute()) {
                        throw GradleException("[local-rewrite] ${misses.joinToString { it.first.identity }} " +
                                "are not cached and $modw is missing — build :mod:devFatJar first")
                    }
                    logger.lifecycle("[local-rewrite] ${misses.joinToString { it.first.identity }} — " +
                            "not cached, registering (this is the slow path)")

                    // Grouped by ecosystem, one CLI start each: every start pays for a JVM plus an
                    // RPC handshake, and both subcommands accept a list.
                    for ((ecosystem, group) in misses.groupBy { it.first.ecosystem }) {
                        val process = ProcessBuilder(
                                listOf(modw.absolutePath, "config", "recipes", ecosystem, "install") +
                                        group.map { it.first.coordinate })
                            .directory(rootDir)
                            .inheritIO()
                            .apply {
                                environment()["MODERNE_CLI_HOME"] = cliHome.absolutePath
                                rpcServer?.let { environment()["REWRITE_DOTNET_RPC_SERVER"] = it.absolutePath }
                            }
                            .start()
                        if (process.waitFor() != 0) {
                            throw GradleException("[local-rewrite] `mod config recipes $ecosystem install` failed")
                        }
                    }

                    val installed = readMarketplace(csv)
                    for ((artifact, digest) in misses) {
                        val fresh = installed.filter { artifact.ownsRow(it) }
                        if (fresh.isEmpty()) {
                            logger.warn("[local-rewrite] ${artifact.identity} registered no recipes " +
                                    "— not caching")
                            continue
                        }
                        storeCachedRows(cacheRoot, artifact, digest, fresh)
                        logger.lifecycle("[local-rewrite] ${artifact.identity} — ${fresh.size} recipes " +
                                "cached under ${digest.take(12)}")
                    }
                    logger.lifecycle("[local-rewrite] catalogue rebuilt at $csv " +
                            "(${readMarketplace(csv).size} recipes)")
                }
            }
        }

        // The C# recipe build is independent of the fat jar, but when both are asked for it is
        // the more useful order: a compile error in the recipes surfaces before the long jar
        // build rather than after it.
        allprojects {
            if (path == ":mod") {
                tasks.matching { it.name == "devFatJar" }.configureEach {
                    mustRunAfter(":devCsharpRecipesBuild")
                }
            }
        }
    }
}
