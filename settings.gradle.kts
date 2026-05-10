rootProject.name = "rjlink"

include(
    ":core",
    ":irc",
    ":tgbot",
    ":admin",
    ":server",
    ":examples:demo-client",
    ":examples:admin-cli"
)

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
