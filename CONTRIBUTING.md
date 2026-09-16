# Contributing

Jewel Tooling provides conservative static stability estimates for Kotlin composable parameters. Unsupported analysis should return unknown with an explanation.

Use the checked-in Gradle wrapper and JDK 21 for the plugin. The E2E runner and standalone fixture use JDK 25. Keep dependency versions pinned and use public repositories; local Maven repositories are not supported.

Run `./gradlew :test :buildPlugin` before proposing a change. Add a regression case for a changed verdict, reason, or editor behavior. Keep all user-visible strings in the resource bundle. Do not retain Analysis API symbols beyond their session. Preserve cancellation and the bounded analysis budget.

Changes to capture inputs require new screenshots and an updated provenance manifest in the same pull request. Follow the capture runbook in the user guide. Screenshots must come from real test-owned windows at the documented device scale.

Describe the observable change and validation in your pull request. Contributions are licensed under Apache 2.0.

The full local validation commands are:

```sh
./gradlew :test :ktfmtCheck :detekt :e2e:driver-plugin:ktfmtCheck :e2e:driver-plugin:detekt :e2e:runner:ktfmtCheck :e2e:runner:detekt :buildPlugin :verifyPluginStructure :verifyPlugin
./gradlew -p fixtures/standalone test ktfmtCheck detekt
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/prepare-distribution.py
python3 scripts/verify-artifacts.py --images
python3 scripts/check-doc-links.py
```

See the [user guide](docs/user-guide.md#run-the-end-to-end-tests) for the real Gradle and Bazel editor scenarios. The root checks include the Bazel fixture's Kotlin sources. Test-only suppressions keep sequential UI scenarios together and forward failures across thread/process boundaries; do not use them to hide production warnings.

For a release, update `pluginVersion` in `gradle.properties`, validate, and tag that commit as `v<pluginVersion>`. The release workflow rejects a mismatched tag or multiple production ZIPs. It publishes an unsigned plugin ZIP and checksum after CI passes.
