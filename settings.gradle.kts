pluginManagement {
    repositories {
        if (file(".local/maven").exists()) maven { url = uri(".local/maven"); metadataSources { mavenPom(); artifact(); ignoreGradleMetadataRedirection() } }
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroup("com.google.testing.platform") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (file(".local/maven").exists()) maven { url = uri(".local/maven"); metadataSources { mavenPom(); artifact(); ignoreGradleMetadataRedirection() } }
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("androidx\\..*"); includeGroup("com.google.testing.platform") } }
        mavenCentral()
    }
}
rootProject.name = "PregnancyJournal"
include(":app")
include(":release-validation")
project(":release-validation").projectDir=file("validation/release")
