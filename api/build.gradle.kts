// dkBank Developer API (MIT). Other plugins compile against this module.

plugins {
    `java-library`
    `maven-publish`
}

base {
    archivesName.set("dkBank-API")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    compileOnly(libs.paper.api)
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "dkBank-API"
            from(components["java"])
            pom {
                name.set("dkBank-API")
                description.set("Developer API for the dkBank plugin")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}

// Ship the MIT license inside the API jars.
tasks.withType<Jar>().configureEach {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}
