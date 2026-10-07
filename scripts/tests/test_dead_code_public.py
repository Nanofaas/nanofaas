"""Exercise the Gradle analysis on compiled code, including reflective entry points."""
from pathlib import Path
import hashlib
import subprocess


ROOT = Path(__file__).resolve().parents[2]


def test_public_dead_code_report_keeps_cross_module_and_reflective_uses(tmp_path):
    tmp_path = tmp_path / "fixture with spaces"
    def write(path, content):
        target = tmp_path / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    write("settings.gradle", """
plugins { id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0' }
rootProject.name='dead-code-fixture'
include 'app', 'shared'
""")
    write("gradle.properties", (ROOT / "gradle.properties").read_text())
    write("build.gradle", f"""
plugins {{ id 'java' }}
subprojects {{
    apply plugin: 'java'
    repositories {{ mavenCentral() }}
    java.toolchain.languageVersion = JavaLanguageVersion.of(25)
}}
project(':shared') {{ dependencies {{ implementation 'org.slf4j:slf4j-api:2.0.13' }} }}
project(':app') {{ dependencies {{
    implementation project(':shared')
    implementation 'org.slf4j:slf4j-api:2.0.16'
}} }}
apply from: '{ROOT / 'gradle/dead-code-public.gradle'}'
""")
    write("shared/src/main/java/sample/Helper.java", """
package sample;
public class Helper {
    public static String usedAcrossModules() { return "used"; }
    public static String neverCalled() { return "dead"; }
}
""")
    write("shared/src/main/java/sample/UnusedPublicClass.java", """
package sample;
public class UnusedPublicClass { public void unused() {} }
""")
    write("app/src/main/java/sample/App.java", """
package sample;
public class App {
    public static void main(String[] args) {
        System.out.println(Helper.usedAcrossModules());
        System.out.println(it.unimib.datai.nanofaas.common.logging.LogSanitizer.singleLine("used"));
    }
}
""")
    for package, name, target in [
        ("org.springframework.stereotype", "Component", "TYPE"),
        ("org.springframework.context.event", "EventListener", "METHOD"),
        ("org.springframework.context.annotation", "Bean", "METHOD"),
    ]:
        write(f"shared/src/main/java/{package.replace('.', '/')}/{name}.java", f"""
package {package};
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
@java.lang.annotation.Target(java.lang.annotation.ElementType.{target})
public @interface {name} {{}}
""")
    write("shared/src/main/java/sample/ReflectiveBean.java", """
package sample;
@org.springframework.stereotype.Component
public class ReflectiveBean {
    @org.springframework.context.event.EventListener
    public void onEvent(MessageDto event) { System.out.println(event); }
    public void close() {}
    public void unusedHelper() {}
}
""")
    write("shared/src/main/java/sample/MessageDto.java", """
package sample;
public record MessageDto(String text) {}
""")
    write("shared/src/main/java/sample/UnusedDto.java", """
package sample;
public record UnusedDto(String text) {}
""")
    for package, name, body in [
        ("controlplane.config", "ReplicaStatusSnapshotConfiguration", """
public class ReplicaStatusSnapshotConfiguration {
    @org.springframework.context.annotation.Bean
    public int replicaStatusSnapshot() { return 1; }
}
"""),
        ("controlplane.service", "ExecutorBackedInvocationEnqueuer", """
@org.springframework.stereotype.Component
public class ExecutorBackedInvocationEnqueuer { void shutdown() {} }
"""),
        ("sdk.lite.handler", "RuntimeLimits", """
@org.springframework.stereotype.Component
public class RuntimeLimits {
    int activeHandlers() { return 0; }
    int newlyUnusedHook() { return 1; }
}
"""),
        ("common.logging", "LogSanitizer", """
public class LogSanitizer {
    private LogSanitizer() {}
    public static String singleLine(Object value) { return String.valueOf(value); }
}
"""),
        ("common.model", "ConcurrencyControlConfig", """
@org.springframework.stereotype.Component
public class ConcurrencyControlConfig {
    private static final int DEFAULT_TARGET_PER_POD = 1;
    private static final int NEW_UNUSED_CONSTANT = 2;
}
"""),
    ]:
        package = f"it.unimib.datai.nanofaas.{package}"
        write(f"shared/src/main/java/{package.replace('.', '/')}/{name}.java",
              f"package {package};\n{body}")
    write("shared/src/main/java/sample/LoadedProvider.java", """
package sample;
public class LoadedProvider implements Runnable { public void run() {} }
""")
    write("shared/src/main/resources/META-INF/services/java.lang.Runnable",
          "# Dynamic entry point\nsample.LoadedProvider # inline comment\n")
    write("shared/src/main/java/it/unimib/datai/nanofaas/sdk/PublicApi.java", """
package it.unimib.datai.nanofaas.sdk;
public class PublicApi { public String externalCall() { return "api"; } }
""")
    result = subprocess.run(
        [str(ROOT / "gradlew"), "-p", str(tmp_path), "deadCodePublic",
         "--console=plain"],
        capture_output=True, text=True, timeout=180,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "duplicate definition of library class" not in result.stdout + result.stderr
    report = (tmp_path / "build/reports/dead-code-public/unused.txt").read_text()
    assert "sample.UnusedPublicClass" in report
    assert "neverCalled()" in report
    assert "unusedHelper()" in report
    assert "usedAcrossModules()" not in report
    assert "onEvent(" not in report
    assert "close()" not in report
    assert "sample.MessageDto" not in report
    assert "sample.UnusedDto" in report
    assert "sample.LoadedProvider" not in report
    assert "it.unimib.datai.nanofaas.sdk.PublicApi" not in report
    assert "ReplicaStatusSnapshotConfiguration" not in report
    assert "void shutdown()" not in report
    assert "activeHandlers()" not in report
    assert "private LogSanitizer()" not in report
    assert "DEFAULT_TARGET_PER_POD" not in report
    assert "newlyUnusedHook()" in report
    assert "NEW_UNUSED_CONSTANT" in report
    classes = list(tmp_path.glob("*/build/classes/java/main/**/*.class"))
    before = {p: hashlib.sha256(p.read_bytes()).digest() for p in classes}
    second = subprocess.run(
        [str(ROOT / "gradlew"), "-p", str(tmp_path), "deadCodePublic",
         "--rerun-tasks", "--console=plain"],
        capture_output=True, text=True, timeout=180,
    )
    assert second.returncode == 0, second.stdout + second.stderr
    assert before == {p: hashlib.sha256(p.read_bytes()).digest() for p in classes}
