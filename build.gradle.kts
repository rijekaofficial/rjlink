import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
    alias(libs.plugins.shadow) apply false
}

allprojects {
    group = "dev.rjlink"
    version = "1.0.0"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    val sourcesJar by tasks.registering(Jar::class) {
        archiveClassifier.set("sources")
        from(project.the<SourceSetContainer>()["main"].allSource)
    }

    plugins.withId("java-library") {
        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension>("publishing") {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                    artifact(sourcesJar)

                    groupId = project.group.toString()
                    artifactId = project.name
                    version = project.version.toString()

                    pom {
                        name.set("RJLink ${project.name}")
                        description.set("RJLink ${project.name} module")
                    }
                }
            }

            repositories {
                maven {
                    name = "GitHubPackages"
                    val configuredRepo = providers.gradleProperty("gpr.repo").orNull
                    val envRepo = providers.environmentVariable("GITHUB_REPOSITORY").orNull
                    url = uri("https://maven.pkg.github.com/${configuredRepo ?: envRepo ?: "OWNER/REPO"}")
                    credentials {
                        username = providers.gradleProperty("gpr.user").orNull
                            ?: providers.environmentVariable("GITHUB_ACTOR").orNull
                        password = providers.gradleProperty("gpr.key").orNull
                            ?: providers.environmentVariable("GITHUB_TOKEN").orNull
                    }
                }
            }
        }
    }
}
