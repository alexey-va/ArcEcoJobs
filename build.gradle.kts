plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "9.3.0"
    id("io.github.drownek.plugwright") version "2.0.4"
    jacoco
}

group = "ru.ruscrafting"
version = "0.1.19"
description = "Rich EcoJobs interface and LuckPerms-backed boosts for RusCrafting"

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

// Disposable Paper-only control plane for the real reward-path E2E suite.
val e2eSupportSourceSet = sourceSets.create("e2eSupport") {
    java.srcDir("src/test/e2e/support/src/main/java")
    resources.srcDir("src/test/e2e/support/src/main/resources")
    compileClasspath += sourceSets.main.get().output
}
configurations["e2eSupportCompileOnly"].extendsFrom(configurations["compileOnly"])

repositories {
    mavenCentral()
    maven("https://repo.rus-crafting.ru/grocermc/") {
        content { includeGroupByRegex("ru\\.ruscrafting\\.(arc|thirdparty)") }
    }
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.auxilor.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://jitpack.io")
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { jvmToolchain(25) }

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ru.ruscrafting.arc:arc-core:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-menu:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-paper:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-paper-menu:2.7.4")
    implementation("ru.ruscrafting.arc:arc-core-sql:2.7.4")
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.willfp:EcoJobs:2026.33")
    compileOnly("com.willfp:eco:2026.33")
    compileOnly("com.willfp:libreforge:2026.33:shadow")
    compileOnly("com.willfp:libreforge-loader:2026.33")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    compileOnly("net.luckperms:api:5.5")
    compileOnly("ru.ruscrafting.thirdparty:rediseconomy:4.5.12")
    compileOnly("me.clip:placeholderapi:2.12.3")

    testImplementation("io.kotest:kotest-runner-junit5:6.0.7")
    testImplementation("io.kotest:kotest-assertions-core:6.0.7")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("com.willfp:EcoJobs:2026.33") { isTransitive = false }
    testImplementation("com.willfp:eco:2026.33") { isTransitive = false }
    testImplementation("com.willfp:libreforge:2026.33:shadow") { isTransitive = false }
    testImplementation("com.willfp:libreforge-loader:2026.33") { isTransitive = false }
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    testImplementation("net.luckperms:api:5.5")
    testImplementation("ru.ruscrafting.thirdparty:rediseconomy:4.5.12")
    testImplementation("me.clip:placeholderapi:2.12.3")
    testImplementation("ru.ruscrafting.arc:arc-core-paper-testing:2.7.4")
    testImplementation("org.yaml:snakeyaml:2.5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "integrationTestImplementation"(sourceSets.test.get().output)
    "integrationTestImplementation"("ru.ruscrafting.arc:arc-core-integration-testing:2.7.4")
    configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
    configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])
}

tasks {
    processResources {
        filesMatching("plugin.yml") { expand("version" to project.version) }
    }
    test {
        useJUnitPlatform()
        systemProperty("arcecojobs.projectDir", projectDir.absolutePath)
        providers.gradleProperty("ruscraftingOpsRoot")
            .orElse(providers.environmentVariable("RUSCRAFTING_OPS_ROOT"))
            .orNull
            ?.let { systemProperty("ruscrafting.opsRoot", it) }
    }
    register<Test>("integrationTest") {
        description = "Runs disposable MySQL storage integration tests."
        group = "verification"
        testClassesDirs = integrationTestSourceSet.output.classesDirs
        classpath = integrationTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
    }
    jar { archiveClassifier.set("plain") }
    shadowJar {
        archiveClassifier.set("")
        mergeServiceFiles()
        exclude("org/slf4j/**")
    }
    check { dependsOn(shadowJar, "integrationTest") }
}

val e2eSupportJar by tasks.registering(Jar::class) {
    archiveFileName.set("ArcEcoJobsE2ESupport.jar")
    from(e2eSupportSourceSet.output)
    dependsOn(e2eSupportSourceSet.classesTaskName)
}

val ecoJobsRuntimeJar = providers.gradleProperty("e2eEcoJobsJar")
    .orElse(layout.projectDirectory.file("e2e-ecojobs/bin/EcoJobs v2026.33.jar").asFile.absolutePath)
val e2eJobIds = listOf(
    "beekeeper", "builder", "enchanter", "explorer", "farmer", "fisherman",
    "lumberjack", "miner", "slayer", "smelter", "toolsmith"
)
val plugwrightLibreforge by configurations.creating
dependencies {
    add(plugwrightLibreforge.name, "com.willfp:libreforge:2026.33:shadow") { isTransitive = false }
}
// Match the upstream loader's nested payload name and eco Kotlin namespace.
val prepareLibreforgeRuntime by tasks.registering(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
    archiveFileName.set("libreforge-2026.33-shadow.jar")
    configurations = emptyList()
    from({ zipTree(plugwrightLibreforge.singleFile) })
    relocate("kotlin", "com.willfp.eco.libs.kotlin")
    relocate("org.jetbrains.kotlin", "com.willfp.eco.libs.kotlin.jetbrains")
}
val prepareEcoJobsRuntime by tasks.registering(Jar::class) {
    archiveFileName.set("EcoJobs-2026.33-e2e.jar")
    from({ zipTree(file(ecoJobsRuntimeJar.get())) }) { exclude("libreforge-2026.33-shadow.jar") }
    from(prepareLibreforgeRuntime)
}
plugwright {
    minecraftVersion.set("26.1.2")
    runDir.set(layout.buildDirectory.dir("plugwright"))
    testsDir.set(layout.projectDirectory.dir("src/test/e2e"))
    downloadNode.set(true)
    nodeVersion.set("22.14.0")
    acceptEula.set(true)
    jvmArgs.set(listOf("-Xms512M", "-Xmx2G", "-XX:ActiveProcessorCount=2"))
    downloadPlugins {
        url("https://cdn.modrinth.com/data/E4spwmyA/versions/ihIOXEWh/eco-2026.33-modrinth.jar")
        url("https://cdn.modrinth.com/data/Vebnzrzj/versions/b0mk8uS6/LuckPerms-Bukkit-5.5.71.jar")
        url("https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.12.3/PlaceholderAPI-2.12.3.jar")
        url("https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar")
        url("https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/rediseconomy/4.5.12/rediseconomy-4.5.12.jar")
    }
    writeFiles {
        file("server.properties", projectDir.resolve("src/test/e2e/fixtures/server.properties"))
        file("plugins/ArcEcoJobs/config.yml", projectDir.resolve("src/main/resources/config.yml").readText()
            .replaceFirst("default: ru", "default: en")
            .replaceFirst("use-client-locale: true", "use-client-locale: false")
            .replaceFirst("require-money-placeholder: true", "require-money-placeholder: false")
            .replaceFirst("minimum-kills: 120", "minimum-kills: 20")
            .replaceFirst("minimum-seconds: 180", "minimum-seconds: 30")
            .replaceFirst("cooldown-seconds: 1800", "cooldown-seconds: 60"))
        file("plugins/EcoJobs-2026.33.jar", prepareEcoJobsRuntime.get().archiveFile.get().asFile)
        e2eJobIds.forEach { jobId ->
            file("plugins/EcoJobs/jobs/$jobId.yml", projectDir.resolve("src/test/e2e/fixtures/ecojobs/jobs/$jobId.yml"))
        }
        file("plugins/ArcEcoJobsE2ESupport.jar", e2eSupportJar.get().archiveFile.get().asFile)
        file("plugins/RedisEconomy/config.yml", projectDir.resolve("src/test/e2e/fixtures/rediseconomy.yml"))
    }
}
tasks.named("plugwrightTest") { dependsOn(prepareEcoJobsRuntime, e2eSupportJar) }
