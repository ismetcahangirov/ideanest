plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "az.ideanest"
version = "0.0.1-SNAPSHOT"
description = "IdeaNest API"

java {
    // A toolchain, not `sourceCompatibility`: the build then produces the same
    // bytecode on a developer machine running any JDK as it does in CI.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Validates the access token on every request, and signs it. Nimbus
    // underneath, which is the reference JOSE implementation for the JVM.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Argon2id. Spring Security's Argon2PasswordEncoder delegates to
    // BouncyCastle, which is pure Java — unlike argon2-jvm, which binds a
    // native library through JNA and turns "does the container have libargon2"
    // into a start-up question.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    // The starter rather than flyway-core alone. Spring Boot 4 broke
    // spring-boot-autoconfigure into per-technology modules, so Flyway's
    // auto-configuration now ships in spring-boot-flyway; with only flyway-core on
    // the path nothing runs the migrations and Hibernate validates against an
    // empty schema.
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    // Flyway 10 split its database support out of the core artifact. Without
    // this the service starts and then fails on the first migration with a
    // message about an unsupported database.
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // The OpenAPI 3.1 document §10.1 says the public API is described by, built
    // from the controllers rather than written beside them (#136). The `-api`
    // starter and not `-ui`: this service publishes a contract, and Swagger UI is
    // a web application with its own asset pipeline that nothing here needs — the
    // browsable version belongs wherever the documentation is hosted, not in the
    // deployed jar. 3.x is the line that supports Spring Boot 4; 2.x targets
    // Spring Framework 6 and does not start here.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.0")

    // UUID v7: time-ordered, so primary keys are generated in the application
    // without giving up index locality the way v4 does. Java has no built-in.
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")

    // `bootRun` starts and stops the local compose stack. Development only, so
    // it never reaches the deployed jar.
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 moved TestRestTemplate out of spring-boot-test into its own
    // module, and it needs RestTemplateBuilder from spring-boot-restclient to be
    // built. Neither arrives with the starter any more, so both are named here.
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    // A real PostgreSQL, not an in-memory substitute. An in-memory database
    // does not reproduce PostgreSQL locking, constraints, or numeric
    // semantics -- precisely the behaviour this platform depends on.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2 renamed every module to carry the project's own prefix,
    // so `junit-jupiter` and `postgresql` no longer name anything. Versions still
    // come from Spring Boot's platform; only the artefact ids moved.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // Module boundaries that are only written down are module boundaries that
    // erode. These are the same rules as az/ideanest/package-info.java, checked.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
    // The provider stub. Google and Apple are not called from a test — a suite
    // that depends on somebody else's uptime fails for reasons that are not
    // ours, and neither of them will sign a token for a key we control. The
    // standalone artefact shades its own Jetty and Jackson, so the stub cannot
    // drag the application's versions around underneath it.
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // A warning nobody is forced to read is a warning nobody reads. `serial` is
    // off because we do not rely on serialisation compatibility, and
    // `processing` because Spring's configuration processor is not always on
    // the path.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// #136: rewrite openapi.json from the running application.
//
// A task rather than something `build` does, and that is the point. The contract
// is a reviewed file: a build that regenerated it would make every change to a
// response body invisible in review, which is the same failure as accepting a
// visual snapshot without reading what changed. `check` asserts that the file is
// current; this is how you make it current, deliberately, and then read the diff.
//
// It reuses the ordinary test task's classpath and the ordinary integration test
// container, because the only honest source of the document is the application
// with every controller wired up. `outputs.upToDateWhen { false }` because the
// point of running it is to overwrite a file whose contents Gradle cannot predict.
tasks.register<Test>("exportOpenApi") {
    group = "documentation"
    description = "Regenerates apps/api/openapi.json from the running application."

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("az.ideanest.OpenApiContractTests") }
    systemProperty("openapi.write", "true")
    outputs.upToDateWhen { false }
}
