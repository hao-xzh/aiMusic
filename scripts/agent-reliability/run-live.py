#!/usr/bin/env python3
"""Compile the isolated JVM probe, then run the real-model acceptance harness.

DEEPSEEK_API_KEY is inherited by the one-shot provider child only.  This launcher
does not inspect, print, persist, or pass the key as a command-line argument.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys


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
    parser.add_argument("--skip-compile", action="store_true", help="reuse probes.jar from an unchanged, successful compilation")
    parser.add_argument(
        "--scenarios", type=Path, default=HERE / "live-scenarios.json",
        help="live scenario contract",
    )
    parser.add_argument(
        "--output", type=Path, default=CACHE / "live-results.json",
        help="generated evidence JSON; never source-controlled scenarios/results",
    )
    parser.add_argument(
        "--provider", type=Path, default=HERE / "live-provider.py",
        help="one-shot provider bridge",
    )
    options = parser.parse_args()
    if not options.scenarios.is_file() or not options.provider.is_file():
        raise SystemExit("live scenarios/provider file missing")

    # Reuse the existing isolated compilation contract without changing its
    # scripted probe entrypoint.  The compiler includes every new .kt probe.
    if not options.skip_compile:
        subprocess.run([sys.executable, str(HERE / "run.py"), "--compile-only"], check=True)
    dependencies = [
        jar("org.jetbrains.kotlin", "kotlin-stdlib", "2.0.21"),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.9.0"),
        jar("org.jetbrains", "annotations", "13.0"),
        CACHE / "json-20240303.jar",
        Path.home() / "Library/Android/sdk/platforms/android-36/android.jar",
        ROOT / "android-native/app/build/tmp/kotlin-classes/release",
    ]
    command = [
        str(JAVA),
        "-cp", os.pathsep.join(map(str, [CACHE / "probes.jar", *dependencies])),
        "LiveAgentReliabilityKt", str(options.scenarios), str(options.output), str(options.provider),
    ]
    return subprocess.run(command, check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
