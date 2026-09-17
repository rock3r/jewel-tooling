#!/usr/bin/env python3
"""Check the actual compiler output used by a Compose fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


def verify_listing(listing):
    match = re.search(r"public static final void GreetingRow\([^\n]+\n", listing)
    if not match:
        raise ValueError("GreetingRow was not found in the compiled class")
    method = re.split(r"\n  (?:public|private|protected|static) ", listing[match.end():], maxsplit=1)[0]
    start = re.search(r"invokestatic[^\n]*traceEventStart:\(IIILjava/lang/String;\)V", method)
    end = re.search(r"invokestatic[^\n]*traceEventEnd:\(\)V", method)
    gates = [gate.start() for gate in re.finditer(r"invokestatic[^\n]*isTraceInProgress:\(\)Z", method)]
    if not start or not end or not any(gate < start.start() for gate in gates):
        raise ValueError("The expected trace start signature or its gate is missing")
    if not any(start.start() < gate < end.start() for gate in gates):
        raise ValueError("The trace end must have its own gate")
    if not re.search(r"String example\.GreetingRow \([^\n]+\)", method[:start.start()]):
        raise ValueError("The producer must supply a nonempty compiler info string")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("classpath", type=Path)
    args = parser.parse_args()
    listing = subprocess.run(["javap", "-c", "-p", "-classpath", str(args.classpath), "example.ExampleKt"],
                             check=True, text=True, capture_output=True).stdout
    verify_listing(listing)
    entry = "example/ExampleKt.class"
    if args.classpath.is_dir():
        data = (args.classpath / entry).read_bytes()
    else:
        with zipfile.ZipFile(args.classpath) as archive:
            data = archive.read(entry)
    print(json.dumps({"class": entry, "sha256": hashlib.sha256(data).hexdigest(),
                      "gatedStartAndEnd": True, "compilerInfo": True}))


if __name__ == "__main__":
    main()
