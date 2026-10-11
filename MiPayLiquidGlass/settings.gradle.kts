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
        // Xposed / LSPosed 传统 API 的官方仓库
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "MiPayLiquidGlass"
include(":app")
