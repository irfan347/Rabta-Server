#!/usr/bin/env python3
"""Find an Android Gradle root in a checkout, or safely unpack one source ZIP."""
from pathlib import Path, PurePosixPath
import os
import stat
import sys
import zipfile

SETTINGS = ("settings.gradle.kts", "settings.gradle")
BUILDS = ("build.gradle.kts", "build.gradle")
IGNORED = {".git", ".gradle", "build", "node_modules", ".android-source", "__MACOSX"}


def is_project(path):
    return any((path / s).is_file() for s in SETTINGS) and any(
        (path / "app" / b).is_file() for b in BUILDS
    )


def projects_in(root):
    candidates = []
    for current, dirs, _ in os.walk(root):
        dirs[:] = [d for d in dirs if d not in IGNORED and not (Path(current)/d).is_symlink()]
        path = Path(current)
        if is_project(path):
            candidates.append(path)
            dirs.clear()
    return candidates


def zip_has_project(path):
    try:
        with zipfile.ZipFile(path) as archive:
            names = set(archive.namelist())
            for name in names:
                if PurePosixPath(name).name in SETTINGS:
                    prefix = str(PurePosixPath(name).parent)
                    prefix = "" if prefix == "." else prefix + "/"
                    if any(prefix + "app/" + b in names for b in BUILDS):
                        return True
    except zipfile.BadZipFile:
        pass
    return False


def safe_extract(path, target):
    with zipfile.ZipFile(path) as archive:
        total = sum(i.file_size for i in archive.infolist())
        if total > 256 * 1024 * 1024:
            raise ValueError("Source ZIP exceeds 256 MiB expanded limit")
        for info in archive.infolist():
            relative = PurePosixPath(info.filename)
            if relative.is_absolute() or ".." in relative.parts or "\\" in info.filename:
                raise ValueError("Unsafe path in source ZIP")
            if stat.S_ISLNK(info.external_attr >> 16):
                raise ValueError("Symlinks are not accepted in source ZIP")
        archive.extractall(target)


def locate(root):
    root = Path(root).resolve()
    if is_project(root):
        return root
    candidates = projects_in(root)
    if not candidates:
        archives = []
        for current, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in IGNORED and not (Path(current)/d).is_symlink()]
            archives.extend(Path(current)/f for f in files if f.lower().endswith(".zip") and zip_has_project(Path(current)/f))
        if len(archives) != 1:
            raise ValueError(f"Expected one Android source ZIP; found {len(archives)}. Keep one ZIP or extract the project at the repository root.")
        target = root / ".android-source"
        if target.exists():
            raise ValueError(".android-source already exists; use a clean checkout")
        safe_extract(archives[0], target)
        candidates = projects_in(target)
    if len(candidates) != 1:
        raise ValueError(f"Expected one Android Gradle project; found {len(candidates)}. Remove duplicate source copies.")
    return candidates[0]


if __name__ == "__main__":
    try:
        project = locate(sys.argv[1] if len(sys.argv) > 1 else ".")
        relative = project.relative_to(Path.cwd().resolve())
        value = str(relative)
        if any(c in value for c in "\r\n"):
            raise ValueError("Project path contains a newline")
        print(f"Android Gradle project: {value}")
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                output.write(f"directory={value}\n")
    except ValueError as error:
        sys.exit(str(error))
