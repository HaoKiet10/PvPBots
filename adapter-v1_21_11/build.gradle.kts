import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.paperweight.userdev)
}

kotlin { jvmToolchain(21) }

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    implementation(project(":adapter-api"))
}

// Paper 1.21.11 runs Mojang-mapped at runtime: the plain `jar` output is what gets shadowed into the plugin.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
