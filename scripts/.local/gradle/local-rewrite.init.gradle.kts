// Local-only, uncommitted source linking of the OpenRewrite SDK.
//
// Applied with: ./gradlew <task> -I .local/gradle/local-rewrite.init.gradle.kts
//
// When the gitignored `external/openrewrite/rewrite` symlink exists (pointing at a
// local rewrite checkout/worktree), this includes it as a composite build so the
// rewrite SDK is compiled from source instead of resolved from ~/.m2 / package caches.
// No pTML of rewrite is needed, and parallel git worktrees never collide in ~/.m2.
//
// NOTE on scope: we intentionally do NOT add an explicit `dependencySubstitution`
// block. rewrite supplies its subproject plugin versions from its ROOT project
// (org.openrewrite.build.root), and scoped/explicit substitution makes Gradle
// configure the substituted subprojects before that root contributes the plugin
// classpath -> "plugin ... must include a version number". Auto-substitution
// configures the included build holistically (root first), which resolves cleanly.
// Consequence: every org.openrewrite module the CLI depends on is sourced from the
// rewrite checkout (rewrite-core/java/csharp plus the rest). rewrite's own build
// cache (ge.openrewrite.org) serves most compile outputs, so this stays fast.
//
// Nothing here is committed; settings.gradle.kts and libs.versions.toml are untouched.

// Only act on the top-level build; included builds (e.g. rewrite) must not re-enter.
if (gradle.parent == null) {
    beforeSettings {
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
}
