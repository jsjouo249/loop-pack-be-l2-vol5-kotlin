"""Build the actual W6 revision with a test-only pre-send interruption fixture."""
import io
import pathlib
import shutil
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
REVISION = "4dfa06b"
OUTPUT = ROOT / "apps/commerce-api/build/week6-fixture"
SOURCE = OUTPUT / "source"
SOURCE.mkdir(parents=True, exist_ok=True)
archive = subprocess.check_output(["git", "archive", REVISION], cwd=ROOT)
with tarfile.open(fileobj=io.BytesIO(archive)) as source:
    source.extractall(SOURCE, filter="data")
fixture = SOURCE / "apps/commerce-api/src/main/kotlin/com/loopers/week7fixture/Week6Preparation.kt"
fixture.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(ROOT / "docs/week7/tooling/Week6Preparation.kt", fixture)
build = SOURCE / "apps/commerce-api/build.gradle.kts"
with build.open("a") as target:
    target.write('\ntasks.bootJar { mainClass.set("com.loopers.week7fixture.Week6PreparationKt"); archiveFileName.set("week6-fixture.jar") }\n')
subprocess.run([str(SOURCE / "gradlew"), ":apps:commerce-api:bootJar", "--console=plain", "--max-workers=2"], cwd=SOURCE, check=True)
shutil.copyfile(SOURCE / "apps/commerce-api/build/libs/week6-fixture.jar", OUTPUT / "week6-fixture.jar")
print(f"WEEK7_BASELINE revision={REVISION} artifact={OUTPUT / 'week6-fixture.jar'}")
