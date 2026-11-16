plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    api(project(":adapter-api"))
    compileOnly(libs.paper.api)
    compileOnly(libs.worldguard.bukkit)
    testImplementation("org.yaml:snakeyaml:2.5")
}

// plugin.yml: version and the Kotlin stdlib that Paper downloads at runtime (`libraries:`).
tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "kotlinVersion" to libs.versions.kotlin.get(),
    )
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
