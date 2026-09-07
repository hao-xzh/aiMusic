#!/usr/bin/env python3
"""Compile current Android agent sources and run isolated JVM reliability probes.

This is not an APK, device, real provider, or real music-service test.
Only Android persistence/logging and external boundaries are replaced.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CACHE = ROOT / "android-native/app/build/agent-reliability"
GRADLE = Path.home() / ".gradle/caches/modules-2/files-2.1"
JAVA = Path(os.environ.get(
    "JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
)) / "bin/java"


def jar(group, artifact, version):
    return next((GRADLE / group / artifact / version).glob("*/*.jar"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile-only", action="store_true")
    args = parser.parse_args()
    CACHE.mkdir(parents=True, exist_ok=True)
    json_jar = CACHE / "json-20240303.jar"
    if not json_jar.exists():
        with urllib.request.urlopen(
            "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar",
            timeout=25,
        ) as response:
            json_jar.write_bytes(response.read())
    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21")
    coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0")
    annotations = jar("org.jetbrains", "annotations", "13.0")
    compiler = [
        jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "2.0.21"),
        stdlib,
        jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"),
        coroutines, annotations,
    ]
    existing = ROOT / "android-native/app/build/tmp/kotlin-classes/release"
    if not existing.exists():
        raise SystemExit(
            "Existing release peripheral classes required; this runner never builds an APK."
        )
    android = Path.home() / "Library/Android/sdk/platforms/android-36/android.jar"
    dependencies = [stdlib, coroutines, annotations, json_jar, android, existing]
    source = ROOT / "android-native/app/src/main/java/app/pipo/nativeapp/data"
    files = []
    for area in ["domain", "normalize", "resolve", "queue", "reply", "runtime"]:
        files.extend(sorted((source / "agent" / area).glob("*.kt")))
    files += [source / "agent/execute/AgentActionExecutor.kt", source / "agent/execute/PlayerAgentExecutor.kt",
              source / "agent/task/AgentTaskStore.kt",
              source.parent / "playback/orchestrator/PlaybackOrchestration.kt", source / "NativeTrack.kt",
              source / "PetPersona.kt", source / "PipoRepository.kt", source / "MusicSearchException.kt"]
    files += sorted(HERE.glob("*.kt"))
    output = CACHE / "probes.jar"
    subprocess.run([
        str(JAVA), "-cp", os.pathsep.join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
        "-Xfriend-paths=" + str(existing),
        "-jvm-target", "17", "-classpath", os.pathsep.join(map(str, dependencies)),
        "-d", str(output), *map(str, files),
    ], check=True)
    if not args.compile_only:
        completed = subprocess.run([
            str(JAVA), "-cp", os.pathsep.join(map(str, [output, *dependencies])),
            "AgentReliabilityKt", str(CACHE / "results.json"),
        ], check=False)
        return completed.returncode
    return 0


if __name__ == "__main__":
    sys.exit(main())
