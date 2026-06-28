plugins {
    java
}

group = "cn.starry"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(
            "version" to project.version,
            "description" to project.description.orEmpty()
        )
    }
}
