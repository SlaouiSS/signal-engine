import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
    jacoco
}

group = "org.signalengine"
version = "0.1.0-SNAPSHOT"
description = "Signal Engine backend — API, orchestration, persistence coordination"

java {
    toolchain {
        // Target is Java 25 LTS (docs/03-technical-spec.md D1); Java 21 LTS is
        // the documented accepted fallback and is what the current toolchain
        // provides. Raise this to 25 once a 25 toolchain is available.
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // Semantic chunking behind the RAG core `Chunker` contract
    // (docs/adr/0010-indexing-foundation-and-semantic-chunking.md). -core only:
    // Apache-2.0, no runtime transitive dependencies. The library is reached
    // through a language model, wired to the Python AI capability path in
    // infrastructure; the generic RAG core never sees this dependency.
    implementation(libs.semantic.chunker.core)

    // Deterministic HTML content extraction for real HTTP sources — see
    // gradle/libs.versions.toml for why jsoup and not a readability framework.
    implementation(libs.jsoup)

    // Persistence: Spring Data JDBC (docs/03-technical-spec.md D20) over
    // PostgreSQL. No JPA/Hibernate. Repository ports live in the application
    // layer; the Spring Data JDBC adapters live in infrastructure.
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    // Spring Boot 4 extracted the Flyway integration into its own module; it
    // brings flyway-core transitively.
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4.1 moved the web MVC test slice (@WebMvcTest, @AutoConfigureMockMvc)
    // out of spring-boot-test-autoconfigure into its own module.
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x renamed its modules with a "testcontainers-" prefix.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Only the executable Spring Boot jar is a deliverable; skip the "-plain" library jar.
tasks.named<Jar>("jar") { enabled = false }

tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:deprecation") }

tasks.withType<Test>().configureEach { useJUnitPlatform() }

// The Java<->Python AI capability contract fixtures live once, next to the Python
// service that owns them (docs/adr/0006-ai-java-python-foundation.md). Copy them
// onto the test classpath so AiCapabilityContractTest can load them as resources.
tasks.named<ProcessResources>("processTestResources") {
    from("../agents/contract") { into("contract/ai") }
}

// Fast job (docs/03-technical-spec.md Section 16.1, 18.1): unit + API/context tests,
// no Docker required.
tasks.test {
    useJUnitPlatform { excludeTags("integration", "benchmark") }
    finalizedBy(tasks.jacocoTestReport)
}

// Separate job (docs/03-technical-spec.md Section 16.1, 16.5, 18.1): Flyway
// migrations and repository/query behaviour against a real PostgreSQL + pgvector
// via Testcontainers. Requires Docker. Not wired into `check`/`build` so the
// fast path stays Docker-free; CI runs it as its own required step.
val testSourceSet = sourceSets.test.get()
val integrationTest =
    tasks.register<Test>("integrationTest") {
        description = "Runs integration tests against a real PostgreSQL (Testcontainers)."
        group = "verification"
        useJUnitPlatform { includeTags("integration") }
        dependsOn(tasks.testClasses)
        shouldRunAfter(tasks.test)
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
    }

// Opt-in job (docs/07-rag.md Section 24, Task 8.3C Step 12): the real end-to-end
// semantic-retrieval quality benchmark — query -> embeddinggemma -> exact pgvector
// cosine search — over the Task 8.3A corpus and human-authored relevance judgments.
// Requires Docker AND the Python `embed` capability service reachable; individual
// tests self-skip (JUnit assumptions) when the embedding service is absent. Never
// wired into `check`/`build`; run explicitly with `./gradlew retrievalBenchmark`.
tasks.register<Test>("retrievalBenchmark") {
    description = "Runs the end-to-end semantic-retrieval quality benchmark (Docker + embed service)."
    group = "verification"
    useJUnitPlatform { includeTags("benchmark") }
    dependsOn(tasks.testClasses)
    shouldRunAfter(tasks.test)
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    // A benchmark is a measurement, not a pass/fail gate; always re-run when asked.
    outputs.upToDateWhen { false }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

configure<SpotlessExtension> {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
