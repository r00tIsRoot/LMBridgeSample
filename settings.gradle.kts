pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // LMBridge 배포 저장소 (GitHub Pages Maven)
        maven { url = uri("https://r00tisroot.github.io/packages/") }
    }
}

rootProject.name = "LMBridgeSample"
include(":app")
