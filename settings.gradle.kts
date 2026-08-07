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
        // livekit-android pulls com.github.davidliu:audioswitch from JitPack.
        maven { url = uri("https://jitpack.io") }
        // The Nosmai SDK ships as a local .aar rather than from a repository.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "NosmaiLiveKitExample"
include(":app")
