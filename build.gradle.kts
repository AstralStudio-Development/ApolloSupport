plugins {
    java
}

group = "moe.illusory"
version = "1.0.1"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(8))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    testImplementation("net.md-5:bungeecord-api:1.21-R0.4")
    testImplementation("junit:junit:4.13.2")
}

val pluginProperties = mapOf(
    "version" to project.version.toString(),
    "description" to project.description.orEmpty()
)

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.properties(pluginProperties)
    filesMatching("plugin.yml") {
        expand(pluginProperties)
    }
}
