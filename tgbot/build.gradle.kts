plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    `java-library`
}

dependencies {
    api(project(":core"))

    implementation(libs.ktgbotapi)

    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.logback.classic)
}

tasks.shadowJar {
    archiveClassifier.set("")
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
    archiveClassifier.set("slim")
}

val sourcesJar by tasks.existing

artifacts {
    add("archives", tasks.shadowJar)
    add("archives", sourcesJar)
}

