// Example addon showing how to use the dkBank Developer API. Not part of dkBank itself.
// In your own plugin, use the published artifact instead of project(":api"):
//   compileOnly("dev.direk:dkBank-API:<version>")

plugins {
    java
}

dependencies {
    compileOnly(project(":api"))   // dkBank provides these classes at runtime
    compileOnly(libs.paper.api)
}

tasks {
    jar {
        archiveFileName.set("dkBankExample-${project.version}.jar")
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
}
