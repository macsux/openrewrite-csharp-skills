// Make the rewrite build's jars byte-reproducible.
//
// Applied to a rewrite build with:  ./gradlew <task> -I .local/gradle/reproducible-info.init.gradle.kts
//
// WHY
//
// The recipe catalogue cache keys each registered artifact on its content, so that an artifact
// rebuilt from unchanged sources — in another worktree, or after an unrelated commit — reuses the
// registration instead of paying for another RPC round trip through every recipe it contains.
// That only works if "unchanged sources" implies "identical bytes".
//
// For the .NET recipe assemblies that holds once the build passes the usual determinism flags.
// For rewrite's jars it very nearly holds already: the openrewrite build plugin normalises entry
// timestamps (everything is stamped 1980-02-01) and entry order, so of the 636 entries in
// rewrite-core.jar, exactly TWO differ between two builds of identical sources:
//
//   META-INF/MANIFEST.MF            Build-Date / Build-Date-UTC
//   META-INF/<module>.properties    the same two values again
//
// Both come from nebula.plugin.info: BasicInfoPlugin contributes the entries to InfoBrokerPlugin,
// InfoJarManifestPlugin writes them into the manifest, and the writeManifestProperties task
// (InfoPropertiesFile) writes them into the properties file. They are wall-clock stamps and
// nothing reads them back.
//
// HOW
//
// nebula already models exactly this distinction: every ManifestEntry carries an `isChanging()`
// flag, and the plugin's own buildNonChangingManifest() is buildManifest() minus the changing
// ones. Today that set is precisely {Build-Date, Build-Date-UTC}.
//
// So rather than naming those two — which would quietly stop working the day nebula adds another
// clock- or host-derived entry — this drops every entry nebula itself marks as changing. The
// version, git SHA, branch and build-status entries are all non-changing and survive, so the jar
// still carries its provenance.
//
// This reaches into InfoBrokerPlugin's private `manifestEntries` list because the plugin exposes
// no removal API (only add/watch/build*). If a future nebula changes that shape, the reflection
// fails soft: the build proceeds with a warning and the jars merely go back to being
// non-reproducible, which costs cache hits and nothing else.

gradle.projectsEvaluated {
    var stripped = 0
    var projectsTouched = 0

    gradle.rootProject.allprojects.forEach { project ->
        val broker = project.plugins
            .firstOrNull { it.javaClass.name == "nebula.plugin.info.InfoBrokerPlugin" }
            ?: return@forEach

        try {
            val field = broker.javaClass.getDeclaredField("manifestEntries")
            field.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val entries = field.get(broker) as MutableList<Any>

            val before = entries.size
            entries.removeAll { entry ->
                entry.javaClass.getMethod("isChanging").invoke(entry) == true
            }
            stripped += before - entries.size
            projectsTouched++
        } catch (e: Exception) {
            project.logger.warn("[reproducible-info] could not strip changing manifest entries " +
                    "from ${project.path} (${e.javaClass.simpleName}: ${e.message}); " +
                    "its jar will not be byte-reproducible")
        }
    }

    if (stripped > 0) {
        println("[reproducible-info] dropped $stripped changing manifest entrie(s) across " +
                "$projectsTouched project(s)")
    }
}
