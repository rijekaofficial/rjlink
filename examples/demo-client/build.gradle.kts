plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("rjlink.examples.DemoClientKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":irc"))
    implementation(libs.logback.classic)
}
