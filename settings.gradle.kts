pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net")
        maven("https://repo.lunarclient.dev")
        maven("https://mvn.exceptionflug.de/repository/exceptionflug-public")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
}

rootProject.name = "ApolloSupport"
