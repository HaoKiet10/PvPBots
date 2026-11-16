plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
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
