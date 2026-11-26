pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Kizuku"
include(":terminal", ":core", ":api", ":server", ":manager", ":test-client", ":compat", ":dhizuku-compat")
