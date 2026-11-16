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
