import xyz.jpenilla.runpaper.task.RunServer
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core"))
    implementation(project(":adapter-api"))
    // Paper 1.21.11+ runs Mojang-mapped: shadow the plain (Mojang-mapped) adapter jars, not a reobf variant.
    implementation(project(":adapter-v1_21_11"))
}

tasks.shadowJar {
    archiveBaseName.set("pvp-bots")
    archiveClassifier.set("")
    // Kotlin stdlib is delivered at runtime via plugin.yml `libraries:`
    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
    }
    exclude("kotlin/**", "org/jetbrains/annotations/**", "org/intellij/lang/annotations/**")
    manifest { attributes("paperweight-mappings-namespace" to "mojang") }
}

tasks.jar { enabled = false }
tasks.assemble { dependsOn(tasks.shadowJar) }

// ---- dev servers (run-paper) ----

// WorldEdit 7.4.6 + WorldGuard 7.0.17 (Bukkit/Paper builds for 1.21.11) from Modrinth. Pinned by Modrinth version
// id: the version number alone also matches WorldEdit's Fabric/NeoForge build of the same number.
fun RunServer.devServer() {
    minecraftVersion("1.21.11")
    // WorldEdit 7.4.x / WorldGuard 7.0.17 are compiled for Java 25 (class file 69); Paper 1.21.11 runs fine on 25.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    downloadPlugins {
        modrinth("worldedit", "YFnqiUlL")
        modrinth("worldguard", "pI4UHLJL")
    }
}

/** The user accepted the Minecraft EULA for local dev servers. */
fun acceptEula(dir: File) {
    dir.mkdirs()
    File(dir, "eula.txt").writeText("eula=true\n")
}

tasks.runServer {
    devServer()
    val dir = runDirectory.get().asFile
    doFirst { acceptEula(dir) }
}

val selfTestDir = layout.projectDirectory.dir("run-selftest").asFile

/**
 * Starts a 1.21.11 server that runs the in-server self-test at startup and shuts down; then checks
 * plugins/PvpBots/selftest-result.txt and scans the world folders for files named after any spawned bot (D1).
 * `-Pselftest=trainer|ally|all` picks the checks (default all).
 */
val selfTest = tasks.register<RunServer>("selfTest") {
    group = "verification"
    description = "Runs the PvpBots in-server self-test on a 1.21.11 dev server."
    devServer()
    runDirectory.set(selfTestDir)
    jvmArgs("-Dpvpbots.selftest=" + (project.findProperty("selftest") ?: "all"))
    args("--port", "25591")
    // If the plugin never runs (e.g. a dependency failed to load) the server would never stop by itself.
    timeout.set(Duration.ofMinutes(15))
    val dataDir = File(selfTestDir, "plugins/PvpBots")
    val resultFile = File(dataDir, "selftest-result.txt")
    val botsFile = File(dataDir, "selftest-bots.txt")
    doFirst {
        acceptEula(selfTestDir)
        resultFile.delete()
        botsFile.delete()
        File(selfTestDir, "pvpbots_selftest").deleteRecursively()
    }
    doLast {
        if (!resultFile.isFile) throw GradleException("Self-test produced no result file: $resultFile")
        val ids = if (botsFile.isFile) botsFile.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() } else emptyList()
        val playerDirs = setOf("playerdata", "players", "stats", "advancements")
        val skipTop = setOf("plugins", "libraries", "cache", "versions", "logs", "config")
        val hits = selfTestDir.walkTopDown()
            .onEnter { d -> d == selfTestDir || d.parentFile != selfTestDir || d.name !in skipTop }
            .filter { f ->
                f.isFile && f.relativeTo(selfTestDir).invariantSeparatorsPath.split('/').dropLast(1).any { it in playerDirs } &&
                    ids.any { f.name.lowercase().contains(it) }
            }
            .map { it.relativeTo(selfTestDir).invariantSeparatorsPath }
            .toList()
        val usercache = File(selfTestDir, "usercache.json").takeIf { it.isFile }?.readText()?.lowercase().orEmpty()
        val cacheHits = ids.count { usercache.contains(it) }
        val d1Pass = ids.isNotEmpty() && hits.isEmpty() && cacheHits == 0
        val d1 = (if (d1Pass) "PASS" else "FAIL") +
            " D1 no save: ${ids.size} fake-player UUIDs scanned; files under playerdata/players/stats/advancements naming one: " +
            "${hits.size}${if (hits.isEmpty()) "" else " $hits"}; usercache.json entries: $cacheHits"
        resultFile.appendText(d1 + "\n")
        val text = resultFile.readText()
        logger.lifecycle("---- selftest-result.txt ----\n$text-----------------------------")
        val lines = text.lines()
        val failed = lines.filter { it.startsWith("FAIL") || it.startsWith("ERROR") }
        val passed = lines.count { it.startsWith("PASS") }
        if (lines.none { it.startsWith("DONE") }) throw GradleException("Self-test did not finish (no DONE line).")
        if (failed.isNotEmpty() || passed == 0) {
            throw GradleException("Self-test failed: ${failed.size} failing check(s):\n" + failed.joinToString("\n"))
        }
    }
}
