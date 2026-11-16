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
    paperweight.paperDevBundle("26.2.build.135-stable")
}

kotlin { jvmToolchain(25) }

sourceSets.main {
    kotlin.srcDir("src/main/kotlin")
    kotlin.srcDir("../common/src/main/kotlin")
    resources.srcDir("../common/src/main/resources")
}



tasks.runServer {
    minecraftVersion("26.2")
    if (providers.gradleProperty("spikeManual").isPresent) jvmArgs("-Dspike.manual=true")
}
