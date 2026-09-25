# Contributing

Use JDK 25, Python 3.10+, and the checked-in Gradle wrapper. Gradle provisions the pinned compilation toolchains. The first build needs network access for dependencies and the IDE distribution. Bazelisk and a graphical session are required only for the integration workflows. Keep dependency versions pinned and use public repositories. Local Maven repositories are not supported.

Start with [AGENTS.md](AGENTS.md). Use the [architecture](docs/architecture.md), [conventions](docs/conventions.md), [testing](docs/testing.md), and [static analysis](docs/static-analysis.md) guides for implementation work. The [user guide](user-guide/README.md) describes supported behaviour. Follow [docs style](docs/DOCS-STYLE.md) when you write public pages.

```sh
python3 scripts/validate.py fast
python3 scripts/validate.py full
```

Inspect `git status --short` before and after work. Keep unrelated edits intact. Report checks that fail before your change separately from regressions. Do not change existing implementation files owned by concurrent tasks without coordination. Use `.plans/` for local proposals. Do not link public documents to that ignored directory.

## Agent discovery

`AGENTS.md` is the canonical instruction file. Each workflow under `.agents/skills/` is the maintained skill for that task. Edit only those files. No hook or global permission configuration is required.

The exception is `.agents/skills/babysit-pr/`. It is vendored from [rock3r/babysit-pr-skill](https://github.com/rock3r/babysit-pr-skill) at the tag in its `VERSION` file. Do not edit it in place. Update it with that repository's `sync.py`, and keep this project's settings in its `config.json`.

The validation runner uses `gradlew.bat` on Windows. Headed Windows E2E and capture are not yet validated. Use `python` if your Python 3 installation does not provide `python3`.

## Code style

Kotlin uses ktfmt **kotlinLangStyle** (4-space indentation, kotlinlang.org layout). Keep ktfmt as the only formatter. Do not add formatter overlap, baselines, or blanket suppressions. See [conventions](docs/conventions.md) and [static analysis](docs/static-analysis.md).

## Review and publication

Keep changes small enough to review. Include the behaviour change, validation evidence, and known limits. Do not include private project names, machine paths, credentials, or connection tokens in public artifacts. Contributions use the repository's [Apache 2.0 license](LICENSE).

The public repository is [rock3r/jewel-tooling](https://github.com/rock3r/jewel-tooling). Do not push, create a PR, or release without user approval. Marketplace publication also waits for the user to test the plugin. The existing release workflow reacts to tags; do not create or push a release tag during local validation.

JetBrains Marketplace signing credentials live in 1Password as **Jewel Tooling Marketplace signing**. Local `:buildPlugin` stays unsigned when these environment variables are unset:

- `JEWEL_TOOLING_MARKETPLACE_CERT_CHAIN`
- `JEWEL_TOOLING_MARKETPLACE_PRIVATE_KEY`
- `JEWEL_TOOLING_MARKETPLACE_PRIVATE_KEY_PASSWORD`

Do not copy keys into the checkout.

## Security

See [SECURITY.md](SECURITY.md) for the reporting channel once the repository is public.
