pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pvp-bots"

include("adapter-api", "core", "plugin", "adapter-v1_21_11")
// Adapters are added in later tasks:
// include("adapter-v26_1", "adapter-v26_2")
