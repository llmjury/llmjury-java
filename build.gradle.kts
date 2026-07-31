plugins {
    `java-library`
    id("com.diffplug.spotless") version "8.9.0"
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "com.llmjury"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // The single runtime dependency: Java has no stdlib JSON, and Gson is small, stable, and
    // dependency-free (the HTTP client is java.net.http).
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Compat floor is Java 11: emit bytecode 11 and link only against the Java 11 API,
// on whatever JDK runs the build — CI proves JDK 11/17/21.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(11)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addBooleanOption("Xdoclint:none", true)
    }
}

// The cross-language determinism fixture ships in spec/fixtures/; bundle it onto the test
// classpath so the conformance test reads the same file every LLMJury SDK asserts against.
tasks.named<ProcessResources>("processTestResources") {
    from("spec/fixtures") {
        into("spec/fixtures")
    }
}

tasks.test {
    useJUnitPlatform()
}

// The runnable README sample lives in its own source set so `build` compiles it with the same
// -Werror bar as the library, and `./gradlew runQuickstart` executes it.
sourceSets {
    create("examples") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

configurations["examplesImplementation"].extendsFrom(configurations["implementation"])
configurations["examplesRuntimeOnly"].extendsFrom(configurations["runtimeOnly"])

tasks.register<JavaExec>("runQuickstart") {
    description = "Runs the README quickstart sample against LLMJURY_BASE_URL (default https://api.llmjury.com)."
    group = "application"
    classpath = sourceSets["examples"].runtimeClasspath
    mainClass.set("com.llmjury.sdk.examples.Quickstart")
}

tasks.named("check") {
    dependsOn(tasks.named("compileExamplesJava"))
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates("com.llmjury", "llmjury-sdk", version.toString())

    pom {
        name.set("LLMJury Java SDK")
        description.set(
            "Official Java SDK for LLMJury — run LLM experiments in production: deterministic " +
                "variant assignment, prompt/model comparison, and non-blocking metrics tracking."
        )
        url.set("https://github.com/llmjury/llmjury-java")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("llmjury")
                name.set("LLMJury")
                email.set("support@llmjury.com")
                organization.set("PermuteLab LLC")
                organizationUrl.set("https://llmjury.com")
            }
        }
        scm {
            url.set("https://github.com/llmjury/llmjury-java")
            connection.set("scm:git:git://github.com/llmjury/llmjury-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/llmjury/llmjury-java.git")
        }
    }
}
