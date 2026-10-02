pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "YMPlayer2"
include(":app", ":core", ":designsystem", ":feature:shell", ":feature:clips")
include(":library:local", ":playback:android")
include(":provider:yandex")
include(":library:offline")
include(":headunit:sidebar")
include(":updater:android")
include(":localization")
