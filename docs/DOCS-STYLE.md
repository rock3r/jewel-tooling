# Docs style

Internal note for whoever writes or revises Jewel Tooling markdown. This file is for maintainers. It is not user-facing.

Public pages live under `user-guide/`. They are written so they can later ship as a subtree of [jewel-ui.dev](https://jewel-ui.dev) docs. Internal architecture, testing, and conventions stay in `docs/`.

Copy editorial rules from the specs house style. The live visual reference is [specs.sebastiano.dev/actions](https://specs.sebastiano.dev/actions/). Adapt layout tokens there when a page is published as HTML. In this repository, the markdown itself must already satisfy the reader contract.

## Reader contract

A visitor should understand the subject, current state, and next step before reaching detailed evidence.

- Lead with the outcome. Use a short title and a one-sentence purpose.
- Put a concise summary before long analysis. State what the reader can do, the important limit, and where to go next.
- Keep the high-level path complete on its own. Move implementation detail, history, and edge cases later, or into `docs/`.
- Prefer direct, active sentences. Use common words. Define unavoidable jargon on first use.
- Write for readers who use English as a second language. Avoid idioms, culture-specific jokes, ornamental metaphors, and Latin phrases.
- Keep paragraphs focused on one idea. Split sentences that carry several independent claims. Use lists for genuine sets. Number steps only when order matters.
- Keep claims specific and traceable. Separate measured fact, inference, proposal, and unknown.
- End when the document is complete. Do not add a conclusion that repeats the summary.

## Public vs internal

User-facing pages describe what ships now: install, editor hints, live inspection, recordings, customisation, and agent setup.

Do not put these in `user-guide/`:

- worktree policy, Detekt repair, capture harness internals
- experimental recorder adapter details
- fixture and E2E command recipes except a short pointer to `docs/testing.md`
- milestone talk (`v1`, issue numbers, “not currently scoped”)

Be honest about pre-release state when it affects the reader. 0.9.0 is the first Marketplace candidate. Disk ZIP install remains valid for local builds.

## Writing

- British spelling in prose: `colour`, `behaviour`, `customisation`. American spelling in code, identifiers, and UI labels that the IDE itself uses (`Color Scheme`).
- Comma after `e.g.` and `i.e.`
- Headings in sentence case: “Inspect a function”, not “Inspect a Function”.
- Put code identifiers in backticks.
- Do not use em dashes as a substitute for sentence structure.
- Do not use canned openings, inflated metaphors, “not only … but also”, or uniform bold-label bullets when a table or short prose is clearer.
- Do not promise skippability, frame times, or a performance verdict from stability or inclusive duration.

## Visual aids

Use a screenshot when it proves a UI. Use a table for mappings. Use a numbered list for a procedure. Give every image an accurate alt text. Do not decorate.

## Verify

1. Can a reader state what the plugin does, how to try it, and one real limit after the summary?
2. Does every long page begin with a short orientation?
3. Do relative links resolve from the page that contains them?
4. Run `python3 scripts/check-doc-links.py` after moving or renaming pages.
