"""Compare the supported subset with Python, including random binary64 values."""

from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import math
import random
import struct
import subprocess
import sys
import tempfile


jo, source = sys.argv[1:]
rng = random.Random(20261022)
values = [0.0, -0.0, 0.125, 0.375, 1.005, 2.675, 9.9999999]
tiny = float.fromhex("0x0.0000000000001p-1022")
largest = float.fromhex("0x1.fffffffffffffp+1023")
values += [tiny, largest]

# Exercise neighbors on either side of powers of ten, where the rounded
# coefficient can carry into a new exponent.
for exponent in [-308, -100, -5, -4, -1, 0, 1, 6, 20, 100, 308]:
    value = 10.0 ** exponent
    values += [math.nextafter(value, 0.0), value,
               math.nextafter(value, math.inf)]

for _ in range(64):
    value = struct.unpack(">d", rng.getrandbits(64).to_bytes(8, "big"))[0]
    if math.isfinite(value):
        values.append(value)

cases = []
for value in values:
    precision = rng.choice([0, 1, 2, 6, 17, 30])
    cases += [(value, f".{precision}{mode}") for mode in "feg"]

for value in [0.0, -0.0, 1.25, 1.75, 9.5, 98.5, 99.5, 999.5,
              0.00009994, 0.00009999, 0.0001, 0.00001, 100000.0, 1000000.0]:
    for precision in [0, 1, 2, 3, 4, 6, 17]:
        cases += [(value, f".{precision}g"), (-value, f".{precision}g")]

for value in [0.1, tiny, largest]:
    for precision in [100, 324, 767, 1074, 10000]:
        cases += [(value, f".{precision}{mode}") for mode in "feg"]

invalid = ["", ".", "f", ".f", ".g", "2f", "%.2f", ".-1f", ".-1g", ".2G", ".2F",
           ".2eextra", ".2gextra", ".２f", ".10001f", ".10001g",
           ".2147483648e", ".999999999999999999999f"]


def run(command):
    return subprocess.run(command, text=True, capture_output=True, timeout=120)


def check_backend(backend, directory):
    if backend == "eval":
        command = [jo, "eval", "--check-tree", source]
    else:
        output = str(Path(directory) / f"app.{backend}")
        compiled = run([jo, "compile", f"--{backend}", "--check-tree", source,
                        "-o", output])
        assert compiled.returncode == 0, compiled.stdout + compiled.stderr
        command = [{"js": "node", "python": "python3", "ruby": "ruby"}[backend], output]

    # Each interpreter invocation has a fixed time budget, so compare in
    # batches rather than making the corpus size depend on machine speed.
    batch_size = 32 if backend == "eval" else len(cases)
    for start in range(0, len(cases), batch_size):
        batch = cases[start:start + batch_size]
        arguments = [argument for value, spec in batch
                     for argument in ("v" + repr(value), "s" + spec)]
        expected = "".join(format(value, spec) + "\n" for value, spec in batch)
        actual = run(command + arguments)
        assert actual.returncode == 0, (backend, actual.returncode, actual.stderr,
                                        actual.stdout[-1000:])
        if actual.stdout != expected:
            lines = actual.stdout.splitlines()
            for index, (value, spec) in enumerate(batch):
                wanted = format(value, spec)
                got = lines[index] if index < len(lines) else "<missing>"
                assert got == wanted, (backend, value, spec, wanted, got)
            raise AssertionError(f"{backend}: unexpected extra output")

    for spec in invalid:
        actual = run(command + ["v1.25", "s" + spec])
        assert actual.returncode != 0, (backend, spec, "unexpected success")
        assert "Invalid Float format specification" in actual.stdout + actual.stderr or \
               "Float format precision must be between 0 and 10000" in actual.stdout + actual.stderr, \
               (backend, spec, actual.stdout, actual.stderr)

    # Validate before handling special values, too.
    for value in ["nan", "inf"]:
        actual = run(command + ["v" + value, "s.2G"])
        assert actual.returncode != 0, (backend, value, "unexpected success")
        assert "Invalid Float format specification" in actual.stdout + actual.stderr

    return f"{backend}: {len(cases)} Python comparisons and invalid specifications passed"


with tempfile.TemporaryDirectory() as directory:
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(check_backend, backend, directory)
                   for backend in ["eval", "js", "python", "ruby"]]
        failures = []
        for future in as_completed(futures):
            try:
                print(future.result(), flush=True)
            except Exception as error:
                failures.append(error)
                print(f"FAIL: {error}", file=sys.stderr, flush=True)
        if failures:
            raise failures[0]
