plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("rjlink.examples.admin.AdminCliKt")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":admin"))
    implementation(libs.logback.classic)
}
