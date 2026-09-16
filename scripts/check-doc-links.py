#!/usr/bin/env python3
"""Check repository-relative Markdown links without requiring network access."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
errors = []
for document in [*root.glob("*.md"), *root.joinpath("docs").rglob("*.md")]:
    for match in re.finditer(r"!?\[[^\]]*\]\(([^)]+)\)", document.read_text()):
        target = match.group(1).split("#", 1)[0]
        if not target or re.match(r"[a-z]+:", target):
            continue
        path = (document.parent / target).resolve()
        if not path.is_relative_to(root) or not path.exists():
            errors.append(f"{document.relative_to(root)}: missing local link {target}")
if errors:
    raise SystemExit("\n".join(errors))
print("Repository-relative documentation links verified")
