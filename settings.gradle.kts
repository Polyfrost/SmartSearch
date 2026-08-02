pluginManagement {
    repositories {
        maven {
            name = "Fabric"
            url = uri("https://maven.fabricmc.net/")
        }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencyResolutionManagement.versionCatalogs.create("libs")

    plugins {
        id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
    }
}

rootProject.name = "smartsearch"