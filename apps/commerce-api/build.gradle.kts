plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":modules:kafka"))
    implementation(project(":modules:events"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    implementation("org.apache.httpcomponents.client5:httpclient5")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")

    // querydsl
    kapt("com.querydsl:querydsl-apt::jakarta")

    // test-fixtures
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

// Performance measurements are explicit runs, separate from deterministic regression checks.
tasks.test { useJUnitPlatform { excludeTags("performance") } }

tasks.register<Test>("week4Benchmark") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("performance") }
    systemProperty("spring.profiles.active", "test")
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("week4.phase", providers.gradleProperty("week4Phase").getOrElse("final"))
    systemProperty("week4.output", rootProject.file("docs/week4/measurements").absolutePath)
    jvmArgs("-Xshare:off")
    outputs.upToDateWhen { false }
}

val outboxE2e by sourceSets.creating
configurations[outboxE2e.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[outboxE2e.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
outboxE2e.compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
outboxE2e.runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output

dependencies {
    add(outboxE2e.implementationConfigurationName, project(":apps:commerce-streamer"))
    add(outboxE2e.implementationConfigurationName, "org.testcontainers:mysql")
}

val prepareWeek6Fixture by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("python3", "docs/week7/tooling/build-week6-fixture.py")
    inputs.files(
        rootProject.file("docs/week7/tooling/build-week6-fixture.py"),
        rootProject.file("docs/week7/tooling/Week6Preparation.kt"),
    )
    outputs.file(layout.buildDirectory.file("week6-fixture/week6-fixture.jar"))
}

tasks.register<Test>("outboxE2e") {
    dependsOn(tasks.testClasses, prepareWeek6Fixture)
    testClassesDirs = outboxE2e.output.classesDirs
    classpath = outboxE2e.runtimeClasspath
    useJUnitPlatform()
    maxHeapSize = "768m"
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    systemProperty("spring.profiles.active", "test")
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("week6.fixture.jar", layout.buildDirectory.file("week6-fixture/week6-fixture.jar").get().asFile.absolutePath)
    systemProperty("week7.root", rootDir.absolutePath)
    mustRunAfter(tasks.test)
}

tasks.check { dependsOn("outboxE2e") }
