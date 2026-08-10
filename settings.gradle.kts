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
        // Mirror for de.robv.android.xposed:api:82 (jcenter, де він лежав раніше, закрито)
        maven("https://api.xposed.info/")
    }
}
rootProject.name = "ShadeTweak"
include(":app")
