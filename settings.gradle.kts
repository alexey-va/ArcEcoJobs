pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "ArcEcoJobs"

val arcCoreDir = providers.gradleProperty("arcCoreDir").orNull?.let(::file)
    ?: file("../arc-core")
require(arcCoreDir.resolve("settings.gradle.kts").isFile) {
    "arcCoreDir must point to an arc-core checkout"
}
includeBuild(arcCoreDir)
