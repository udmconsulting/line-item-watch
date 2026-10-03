#!/usr/bin/env python3
"""Verify release-only configuration and runtime roles in the built application JAR."""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: inspect-application-jar.py <application.jar>")
    jar_path = Path(sys.argv[1])
    try:
        with zipfile.ZipFile(jar_path) as archive:
            names = set(archive.namelist())
            forbidden = {
                "BOOT-INF/classes/application-local.yaml",
                "BOOT-INF/classes/application-local.yml",
            }
            present = sorted(forbidden & names)
            if present:
                fail(f"local configuration is packaged: {', '.join(present)}")

            runtime_role_path = (
                "BOOT-INF/classes/com/udmconsulting/platform/runtime/RuntimeRole.class"
            )
            if runtime_role_path not in names:
                fail("compiled RuntimeRole.class is missing")
            runtime_role = archive.read(runtime_role_path)
            for role in (b"SERVICE", b"MIGRATE", b"OPERATOR"):
                if role not in runtime_role:
                    fail(f"compiled runtime role is missing: {role.decode()}")

            application_yaml_path = "BOOT-INF/classes/application.yml"
            if application_yaml_path not in names:
                fail("application.yml is missing")
            application_yaml = archive.read(application_yaml_path).decode("utf-8")
            for role in ("SERVICE", "MIGRATE", "OPERATOR"):
                if f"role: {role}" not in application_yaml:
                    fail(f"runtime profile configuration is missing: {role}")
    except (OSError, zipfile.BadZipFile, UnicodeDecodeError) as error:
        fail(f"cannot inspect application JAR: {error}")

    print("Compiled application roles and local-profile exclusion verified.")


if __name__ == "__main__":
    main()
