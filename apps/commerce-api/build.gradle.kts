plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // querydsl
    kapt("com.querydsl:querydsl-apt::jakarta")

    // test-fixtures
    testImplementation("com.tngtech.archunit:archunit:1.5.0")
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
