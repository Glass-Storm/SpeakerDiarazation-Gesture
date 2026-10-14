pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The `ecosys.v1` contract is consumed from Maven local:
        // `com.glassstorm.phonemanager:contract:<version>`, published by the hub
        // with `./gradlew :contract:publishToMavenLocal`. The hub repo is out of
        // scope for this app, so the artifact (not a source clone) is the edge.
        // Declared here because project-level repositories are forbidden above.
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "SpeakerDiarazationDemo"
include(":app")

// Integrate the gesture-recognition project (Kotlin) as a library module.
include(":gesture")
project(":gesture").projectDir = file("gesture")
 
