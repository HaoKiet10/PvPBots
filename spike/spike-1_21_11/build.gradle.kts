plugins {
    kotlin("jvm")
    id("io.papermc.paperweight.userdev")
    id("xyz.jpenilla.run-paper")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

kotlin { jvmToolchain(21) }

sourceSets.main {
    kotlin.srcDir("src/main/kotlin")
    kotlin.srcDir("../common/src/main/kotlin")
    resources.srcDir("../common/src/main/resources")
}

paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.runServer {
    minecraftVersion("1.21.11")
    if (providers.gradleProperty("spikeManual").isPresent) jvmArgs("-Dspike.manual=true")
}
