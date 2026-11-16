plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.paperweight.userdev) apply false
    alias(libs.plugins.run.paper) apply false
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "pvpbots"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.enginehub.org/repo/")
    }
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencies {
            "compileOnly"(rootProject.libs.kotlin.stdlib)
            "testImplementation"(rootProject.libs.kotlin.stdlib)
            "testImplementation"(rootProject.libs.kotlin.test.junit5)
            "testImplementation"(platform("org.junit:junit-bom:${rootProject.libs.versions.junit.get()}"))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
