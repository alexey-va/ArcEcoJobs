plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    jacoco
}

group = "ru.ruscrafting"
version = "0.1.7"
description = "Rich EcoJobs interface and LuckPerms-backed boosts for RusCrafting"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.auxilor.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("com.mysql:mysql-connector-j:9.7.0")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.willfp:EcoJobs:2026.33")
    compileOnly("com.willfp:eco:2026.33")
    compileOnly("com.willfp:libreforge:2026.33")
    compileOnly("com.willfp:libreforge-loader:2026.33")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("me.clip:placeholderapi:2.12.3")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("com.willfp:EcoJobs:2026.33") { isTransitive = false }
    testImplementation("com.willfp:eco:2026.33") { isTransitive = false }
    testImplementation("com.willfp:libreforge:2026.33") { isTransitive = false }
    testImplementation("com.willfp:libreforge-loader:2026.33") { isTransitive = false }
    testImplementation("net.luckperms:api:5.5")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.110.0")
    testImplementation("org.yaml:snakeyaml:2.5")
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    processResources {
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        systemProperty("arcecojobs.projectDir", projectDir.absolutePath)
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
    }
    check { dependsOn(shadowJar) }
}
