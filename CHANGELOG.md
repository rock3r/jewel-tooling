# Changelog

All notable changes to Jewel Tooling are recorded here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.9.0] - 2026-09-20

First public distribution for IntelliJ IDEA 2026.2 (build 262.8665.337) with bundled Kotlin in K2 mode.

### Added

- Compose parameter stability hints beside `@Composable` parameters in project sources and attached library Kotlin sources
- Hover explanations, function gutter summaries, and **Inspect Compose Stability**
- **Run with Compose Inspection** for a temporary copy of a local Gradle or IntelliJ run configuration
- Live recompositions tool window with execution counts, inclusive durations, site filter, dependency hiding, and clickable duration badges
- Export, import, and close recordings from the tool window or **Tools → Open Compose Recording**
- **Tools → Compose Analysis MCP Server…** and the `jewel-compose-analysis` skill
- Colour settings for stability hints and live duration badges

See the [user guide](user-guide/README.md) for behaviour and limits.

[0.9.0]: https://github.com/rock3r/jewel-tooling/releases/tag/v0.9.0
