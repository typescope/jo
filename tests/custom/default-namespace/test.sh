#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
JO_COMPILER="${JO_COMPILER:-$PROJECT_ROOT/bin/jo}"
cd "$PROJECT_ROOT"

python3 - "$JO_COMPILER" <<'PY'
from pathlib import Path
import hashlib
import subprocess
import sys
import tempfile

compiler = str(Path(sys.argv[1]).resolve())


def compile_jo(*args):
    result = subprocess.run(
        [compiler, "compile", "--check-tree", *map(str, args)],
        capture_output=True, text=True,
    )
    assert result.returncode == 0, result.stdout + result.stderr


def source(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content.encode("utf-8"))
    return path


def library(files, output):
    compile_jo("--sast", output, *files)
    return {p.parent.relative_to(output).as_posix() for p in output.rglob("*.sast")}


def check_import(namespace, library_output, directory):
    consumer = source(
        directory / "consumer.jo",
        f"import {namespace}.value\n\ndef main(): Unit = println(value)\n",
    )
    output = directory / "consumer.py"
    compile_jo("--python", "--lib", library_output, consumer, "-o", output)
    result = subprocess.run([sys.executable, str(output)], check=True, capture_output=True, text=True)
    assert result.stdout == "7\n", (namespace, result.stdout)


with tempfile.TemporaryDirectory(prefix="jo-default-namespace-") as directory:
    work = Path(directory)
    content = "def value: Int = 7\n"
    digest = "a4212db8da0a5060"
    expected = "array_utils_" + digest
    original = source(work / "original" / "Array-Utils.jo", content)
    assert library([original], work / "library") == {expected}

    # Moving a file preserves its name; spaces, hyphens, and underscores normalize alike.
    for index, name in enumerate(["Array-Utils.jo", "Array_Utils.jo", "Array Utils.jo", "ARRAY-UTILS.jo"]):
        moved = source(work / f"moved-{index}" / name, content)
        assert library([moved], work / f"library-{index}") == {expected}

    # Only digit-leading filenames need an added underscore to form a valid identifier.
    # Keyword stems are valid once the hash is appended; imports exercise the lexer too.
    for index, (name, stem) in enumerate([
        ("class.jo", "class"),
        ("namespace.jo", "namespace"),
        ("if.jo", "if"),
        ("123.jo", "_123"),
        ("_Array-Utils.jo", "_array_utils"),
        ("__Array-Utils.jo", "__array_utils"),
        (".jo", ""),
    ]):
        unusual = source(work / f"unusual-{index}" / name, content)
        namespace = stem + "_" + digest
        library_output = work / f"unusual-library-{index}"
        assert library([unusual], library_output) == {namespace}
        check_import(namespace, library_output, work / f"unusual-import-{index}")

    # Content, including UTF-8 comments, determines the suffix. Same-named files coexist.
    changed_content = content + "// café\n"
    changed = source(work / "changed" / "Array-Utils.jo", changed_content)
    changed_name = "array_utils_" + hashlib.sha256(changed_content.encode("utf-8")).hexdigest()[:16]
    assert changed_name != expected
    assert library([original, changed], work / "combined") == {expected, changed_name}

    # A declared namespace continues to override the generated name.
    explicit = source(work / "explicit.jo", "namespace example.library\n\n" + content)
    assert library([explicit], work / "explicit-library") == {"example/library"}

    # Generated namespaces are ordinary namespaces and can be imported from SAST libraries.
    check_import(expected, work / "library", work / "canonical-import")

print("PASS: default namespace naming and compiled-library imports")
PY
