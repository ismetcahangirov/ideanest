plugins {
    java
    id("org.springframework.boot") version "3.5.16"
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

    implementation("org.flywaydb:flyway-core")
    // Flyway 10 split its database support out of the core artifact. Without
    // this the service starts and then fails on the first migration with a
    // message about an unsupported database.
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // UUID v7: time-ordered, so primary keys are generated in the application
    // without giving up index locality the way v4 does. Java has no built-in.
    implementation("com.github.f4b6a3:uuid-creator:6.1.1")

    // `bootRun` starts and stops the local compose stack. Development only, so
    // it never reaches the deployed jar.
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // A real PostgreSQL, not an in-memory substitute. An in-memory database
    // does not reproduce PostgreSQL locking, constraints, or numeric
    // semantics -- precisely the behaviour this platform depends on.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // Module boundaries that are only written down are module boundaries that
    // erode. These are the same rules as az/ideanest/package-info.java, checked.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
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
