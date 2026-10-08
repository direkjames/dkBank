// dkBank plugin (proprietary). Builds one jar for Paper and Purpur 1.21.4 - 26.3.

import xyz.jpenilla.runpaper.task.RunServer
import java.net.URI

plugins {
    java
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

dependencies {
    implementation(project(":api"))
    implementation(libs.hikaricp) {
        exclude(group = "org.slf4j") // the server already provides SLF4J
    }

    compileOnly(libs.paper.api)
    compileOnly(libs.vault.api) { isTransitive = false }
    compileOnly(libs.placeholderapi) { isTransitive = false }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.paper.api)
    testRuntimeOnly(libs.junit.launcher)
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    jar {
        // The real plugin jar is the shaded one below.
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveFileName.set("dkBank-${project.version}.jar")
        // Move bundled libraries into our own package so they can't clash with other plugins.
        relocate("com.zaxxer.hikari", "dev.direk.dkbank.libs.hikari")
        from(rootProject.layout.projectDirectory.file("LICENSE")) { into("META-INF") }
        mergeServiceFiles()
    }

    assemble {
        dependsOn(shadowJar)
    }

    test {
        systemProperty("dkbank.version", project.version.toString())
    }

    // Default `runServer`: newest Paper.
    runServer {
        minecraftVersion("26.3")
        runDirectory.set(rootProject.layout.projectDirectory.dir("run/paper-26.3"))
        javaLauncher.set(launcher(25))
    }
}

// ---------------------------------------------------------------- test servers
// One task per supported version, each in its own folder under run/:
//   ./gradlew runPaper-1.21.4   runPaper-1.21.11   runPaper-26.1   runPaper-26.3
//   ./gradlew runPurpur-1.21.4  runPurpur-1.21.11  runPurpur-26.1  runPurpur-26.3

fun launcher(java: Int) = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(java))
}

/** Purpur isn't built into run-paper, so download its jar ourselves. */
fun purpurJar(version: String): TaskProvider<Task> = tasks.register("downloadPurpur-$version") {
    group = "dkbank test servers"
    description = "Downloads the latest Purpur $version build"
    val target = layout.buildDirectory.file("purpur/purpur-$version.jar")
    outputs.file(target)
    onlyIf { !target.get().asFile.exists() }
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        URI("https://api.purpurmc.org/v2/purpur/$version/latest/download").toURL().openStream().use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
}

data class TestServer(val label: String, val minecraft: String, val purpur: String, val java: Int)

listOf(
    TestServer("1.21.4", "1.21.4", "1.21.4", 21),
    TestServer("1.21.11", "1.21.11", "1.21.11", 21),
    TestServer("26.1", "26.1.2", "26.1.2", 25),
    TestServer("26.3", "26.3", "26.3", 25),
).forEach { server ->
    tasks.register<RunServer>("runPaper-${server.label}") {
        group = "dkbank test servers"
        description = "Runs Paper ${server.minecraft} with dkBank"
        minecraftVersion(server.minecraft)
        runDirectory.set(rootProject.layout.projectDirectory.dir("run/paper-${server.label}"))
        javaLauncher.set(launcher(server.java))
        pluginJars(tasks.shadowJar.flatMap { it.archiveFile })
    }

    val download = purpurJar(server.purpur)
    tasks.register<RunServer>("runPurpur-${server.label}") {
        group = "dkbank test servers"
        description = "Runs Purpur ${server.purpur} with dkBank"
        dependsOn(download)
        minecraftVersion(server.minecraft)
        serverJar(layout.buildDirectory.file("purpur/purpur-${server.purpur}.jar"))
        displayName.set("Purpur")
        runDirectory.set(rootProject.layout.projectDirectory.dir("run/purpur-${server.label}"))
        javaLauncher.set(launcher(server.java))
        pluginJars(tasks.shadowJar.flatMap { it.archiveFile })
    }
}
