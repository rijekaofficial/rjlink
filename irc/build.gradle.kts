plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    `java-library`
}

dependencies {
    api(project(":core"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.logback.classic)
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/INDEX.LIST",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "META-INF/versions/**/module-info.class",
        "module-info.class"
    )
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    archiveClassifier.set("")
}

val sourcesJar by tasks.existing

artifacts {
    add("archives", tasks.shadowJar)
    add("archives", sourcesJar)
}

