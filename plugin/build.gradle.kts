import xyz.jpenilla.runpaper.task.RunServer
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

kotlin { jvmToolchain(21) }

// The 26.x adapters are compiled for Java 25 (Paper 26.x needs it) while the plugin, core and adapter-api stay on 21 so
// the jar still loads on 1.21.11. Without this, Gradle refuses to put Java-25 variants on a Java-21 runtime classpath.
// Safe: an adapter class is only loaded (reflectively, by AdapterLoader) on the server version it was built for.
java { disableAutoTargetJvm() }

dependencies {
    implementation(project(":core"))
    implementation(project(":adapter-api"))
    // Adapters are loaded reflectively by AdapterLoader: runtime-only keeps NMS off this module's compile classpath.
    // Paper 1.21.11+ runs Mojang-mapped and 26.x is unobfuscated: shadow the plain (Mojang-named) adapter jars.
    runtimeOnly(project(":adapter-v1_21_11"))
    runtimeOnly(project(":adapter-v26_1"))
    runtimeOnly(project(":adapter-v26_2"))
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

/**
 * A dev server: Minecraft version plus the WorldEdit / WorldGuard Bukkit builds for it, pinned by Modrinth version id
 * (the version number alone also matches WorldEdit's Fabric/NeoForge build of the same number).
 */
data class DevServer(val minecraft: String, val worldEdit: String, val worldGuard: String)

// 1.21.11: WorldEdit 7.4.6 + WorldGuard 7.0.17.
val mc1_21_11 = DevServer("1.21.11", worldEdit = "YFnqiUlL", worldGuard = "pI4UHLJL")
// 26.1.2 (newest 26.1.x): WorldEdit 7.4.6 + WorldGuard 7.0.18 (Modrinth lists 26.1-26.3 for both).
val mc26_1 = DevServer("26.1.2", worldEdit = "YFnqiUlL", worldGuard = "btHBavWa")
// 26.2: WorldEdit 7.4.6 + WorldGuard 7.0.19 (Modrinth lists 26.2-26.3 for both).
val mc26_2 = DevServer("26.2", worldEdit = "YFnqiUlL", worldGuard = "TtfwTyi6")

fun RunServer.devServer(server: DevServer) {
    minecraftVersion(server.minecraft)
    // Paper 26.x needs Java 25; WorldEdit 7.4.x / WorldGuard 7.0.17+ are compiled for Java 25 (class file 69) too, and
    // Paper 1.21.11 runs fine on 25.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    pluginJars.from(tasks.shadowJar.flatMap { it.archiveFile })
    downloadPlugins {
        modrinth("worldedit", server.worldEdit)
        modrinth("worldguard", server.worldGuard)
    }
}

/** The user accepted the Minecraft EULA for local dev servers. */
fun acceptEula(dir: File) {
    dir.mkdirs()
    File(dir, "eula.txt").writeText("eula=true\n")
}

tasks.runServer {
    devServer(mc1_21_11)
    val dir = runDirectory.get().asFile
    doFirst { acceptEula(dir) }
}

/**
 * Starts a dev server that runs the in-server self-test at startup and shuts down; then checks
 * plugins/PvpBots/selftest-result.txt and scans the world folders for files named after any spawned bot (D1).
 * `-Pselftest=trainer|ally|all|load:N` picks the checks (default all; `load:N` is the N-bot performance check).
 */
fun registerSelfTest(name: String, server: DevServer, runDir: String, port: Int) = tasks.register<RunServer>(name) {
    group = "verification"
    description = "Runs the PvpBots in-server self-test on a ${server.minecraft} dev server."
    devServer(server)
    val selfTestDir = layout.projectDirectory.dir(runDir).asFile
    runDirectory.set(selfTestDir)
    jvmArgs("-Dpvpbots.selftest=" + (project.findProperty("selftest") ?: "all"))
    args("--port", port.toString())
    // If the plugin never runs (e.g. a dependency failed to load) the server would never stop by itself.
    timeout.set(Duration.ofMinutes(15))
    val dataDir = File(selfTestDir, "plugins/PvpBots")
    val resultFile = File(dataDir, "selftest-result.txt")
    val botsFile = File(dataDir, "selftest-bots.txt")
    doFirst {
        acceptEula(selfTestDir)
        resultFile.delete()
        botsFile.delete()
        listOf("pvpbots_selftest", "pvpbots_selftest_2", "pvpbots_selftest_3").forEach { File(selfTestDir, it).deleteRecursively() }
    }
    doLast {
        if (!resultFile.isFile) throw GradleException("Self-test produced no result file: $resultFile")
        val ids = if (botsFile.isFile) botsFile.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() } else emptyList()
        // Player data layouts: 1.21.11 world/playerdata|stats|advancements; 26.x world/players/data|stats|advancements.
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
        logger.lifecycle("---- selftest-result.txt (${server.minecraft}) ----\n$text-----------------------------")
        val lines = text.lines()
        val failed = lines.filter { it.startsWith("FAIL") || it.startsWith("ERROR") }
        val passed = lines.count { it.startsWith("PASS") }
        if (lines.none { it.startsWith("DONE") }) throw GradleException("Self-test did not finish (no DONE line).")
        if (failed.isNotEmpty() || passed == 0) {
            throw GradleException("Self-test failed: ${failed.size} failing check(s):\n" + failed.joinToString("\n"))
        }
    }
}

val selfTest = registerSelfTest("selfTest", mc1_21_11, "run-selftest", 25591)
val selfTest_26_1 = registerSelfTest("selfTest_26_1", mc26_1, "run-selftest-26_1", 25592)
val selfTest_26_2 = registerSelfTest("selfTest_26_2", mc26_2, "run-selftest-26_2", 25593)
