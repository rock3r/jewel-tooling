# Docs style

Maintainers must follow these rules when editing Jewel Tooling Markdown.

Write public pages in `user-guide/` for publication at https://jewel-ui.dev/docs/tooling/.
Keep internal architecture, testing, and conventions in `docs/`.

Apply the specs house style's editorial rules to Markdown before publication.
For HTML layouts, adapt tokens from [specs.sebastiano.dev/actions](https://specs.sebastiano.dev/actions/).

## Reader contract

A page's opening must state its subject, current state, and next step before detailed evidence.

- Start with the outcome. Give the page a short title and one sentence stating its purpose.
- Before long analysis, summarise available actions, the important limit, and the next step.
- Explain the main workflow fully before implementation details, history, and edge cases. Move internal details into `docs/` when appropriate.
- Name the actor and use active sentences. Choose common words and define necessary jargon at first use.
- Assume readers use English as a second language. Exclude idioms, cultural jokes, decorative metaphors, and Latin phrases.
- Give each paragraph one idea. Keep sentences below 25–30 words. Use lists for sets and numbers for ordered steps.
- Support specific claims with traceable evidence. Label measurements, inference, proposals, and unknowns separately.
- Stop after the last necessary detail. Omit conclusions that repeat the summary.

## Public vs internal

Public pages cover released installation, editor hints, live inspection, recordings, customisation, and agent setup.

Keep the following material outside `user-guide/`:

- worktree policy, Detekt repair, capture harness internals
- experimental recorder adapter details
- fixture and E2E command recipes except a short pointer to `docs/testing.md`
- milestone talk (`v1`, issue numbers, “not currently scoped”)

Explain pre-release limits when they affect usage. Version 0.9.0 is the first Marketplace candidate.
Local builds still support installation from a ZIP on disk.

## Writing

- Use British spelling in prose: `colour`, `behaviour`, and `customisation`. Preserve American spelling in code, identifiers, and IDE labels such as `Color Scheme`.
- Follow `e.g.` and `i.e.` with a comma.
- Use sentence case for headings, such as “Inspect a function”.
- Enclose code identifiers in backticks.
- Split sentences instead of joining unrelated claims with em dashes.
- Remove canned openings, decorative metaphors, formulaic contrasts, aphorisms, fragments, and commentary about how readers interpret the page.
- Choose tables or short prose when repeated bold-label bullets obscure the information.
- Keep stability and inclusive duration claims within their limits. Neither establishes skippability, frame times, or performance defects.

## Visual aids

Show screenshots for UI evidence, tables for mappings, and numbered lists for procedures.
Describe every image accurately in alt text. Leave out decorative images.

## Verify

1. Check that the summary names the plugin's purpose, setup path, and one actual limit.
2. Introduce each long page with a short explanation of its scope.
3. Resolve relative links from their containing page.
4. After moving or renaming pages, run `python3 scripts/check-doc-links.py`.
