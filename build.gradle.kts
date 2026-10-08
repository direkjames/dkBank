// Settings shared by the api and plugin modules.

plugins {
    alias(libs.plugins.shadow) apply false
    alias(libs.plugins.run.paper) apply false
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        // Build with JDK 21 and produce Java 21 bytecode: runs on 1.21.x (Java 21) and 26.x (Java 25) servers.
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Xlint:-serial"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
