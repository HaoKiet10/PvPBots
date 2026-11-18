plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.paperweight.userdev)
}

// Paper 26.x requires Java 25; the 26.x server is unobfuscated, so the dev bundle has no remap step (no reobf config).
kotlin { jvmToolchain(25) }

dependencies {
    paperweight.paperDevBundle("26.3.build.253-stable")
    implementation(project(":adapter-api"))
}
