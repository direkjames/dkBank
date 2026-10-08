plugins {
    // Lets Gradle download the Java versions it needs (21 to build, 25 for 26.x test servers).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "dkBank"

// api:    the public Developer API (MIT), published as dkBank-API
// plugin: the plugin itself (proprietary), built as dkBank-<version>.jar
include("api", "plugin", "example")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://jitpack.io") {
            name = "jitpack"
            content { includeGroup("com.github.MilkBowl") } // Vault API only
        }
        maven("https://repo.extendedclip.com/releases/") {
            name = "placeholderapi"
            content { includeGroup("me.clip") }
        }
    }
}
