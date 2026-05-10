plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    `java-library`
}

dependencies {
    api(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(libs.serialization.core)
    api(libs.serialization.cbor)
    api(libs.ktor.client.core)
    api(libs.ktor.client.cio)
    api(libs.ktor.client.websockets)
    api(libs.ktor.network.tls)
    api(libs.slf4j.api)

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
