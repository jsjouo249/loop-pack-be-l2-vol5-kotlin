#!/usr/bin/env bash
set -euo pipefail
# JDK 21 and a user-started Docker daemon must already be available.
repo_root="$(git rev-parse --show-toplevel)"
baseline_dir="$(mktemp -d /private/tmp/looppack-week4-baseline.XXXXXX)"
printf 'Isolated baseline checkout: %s\n' "$baseline_dir"
git -C "$repo_root" archive 491803e | tar -x -C "$baseline_dir"
mkdir -p "$baseline_dir/apps/commerce-api/src/test/kotlin/com/loopers/performance"
cp "$repo_root/docs/week4/tooling/ProductListBaselineBenchmarkTest.kt" "$baseline_dir/apps/commerce-api/src/test/kotlin/com/loopers/performance/"
cat >> "$baseline_dir/apps/commerce-api/build.gradle.kts" <<'GRADLE'

tasks.register<Test>("week4Benchmark") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("performance") }
    systemProperty("spring.profiles.active", "test")
    systemProperty("user.timezone", "Asia/Seoul")
    systemProperty("week4.phase", "baseline-reproduced")
    systemProperty("week4.output", rootProject.file("docs/week4/measurements").absolutePath)
    jvmArgs("-Xshare:off")
    outputs.upToDateWhen { false }
}
GRADLE
(
    cd "$baseline_dir"
    ./gradlew :apps:commerce-api:week4Benchmark --console=plain
)
cp "$baseline_dir/docs/week4/measurements/baseline-reproduced.json" "$repo_root/docs/week4/measurements/"
printf 'Raw baseline retained at %s; remove it manually after review.\n' "$baseline_dir"
