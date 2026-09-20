---
name: spectre-evidence
description: Run maintained standalone and IJPL Spectre workflows, or capture and promote matching native Retina evidence.
---

# Spectre and Retina evidence

Use for rendered editor hints, live controls, target launch, unload, or documentation screenshots.
Require JDK 25, Python 3.10+, Bazelisk, network access for uncached dependencies, and a graphical session.
Run from the repository root. On Linux, use the Xvfb command in `.github/workflows/ci.yml`.

```sh
python3 scripts/validate.py e2e
```

This exports the SDK, builds the real Bazel model, tests the standalone application, checks trace markers, and runs `JewelTargetsTest`.
For a cached IDE, add `--ide-path /path/to/IntelliJ-IDEA.app`.
Inspect `e2e/runner/build/test-results/test/`, `e2e/runner/build/artifacts/`, and `fixtures/standalone/build/`.
Filtered IDE reruns still import the standalone recording. Regenerate that recording with the runner's capture ID before a filtered rerun.
Require all six maintained IDE cases without skips. Do not silently replace them with `EditorSpikeTest`.
Keep `fixtures/ijpl/build/ide-project/model-evidence.json` as evidence of the actual Bazel classpath.

## Promote guide images

Require a native 2.0 AWT device scale, an unlocked desktop, and screen-capture permission.

```sh
./scripts/capture-retina.sh
python3 scripts/verify-artifacts.py --images
python3 scripts/check-doc-links.py
```

The script uses one UUID and takes the source snapshot before tests. Do not reuse a previous run's successful files.
If an agent's Gradle workflow requires a wrapper, execute the script's individual commands through that workflow with the same UUID.
Keep the begin step before builds and the final promotion after every required scenario passes.

Inspect all thirteen images at native resolution. Check light/dark states, circle borders, text hierarchy, focus, and splitter contrast.
Promotion also rewrites `user-guide/images/marketplace/{editor,live,recording}.png` at 1280×800. Inspect those three before a Marketplace upload. Captions are in `captions.txt` beside them.
Promote only matching successful results. Never upscale an image, replace provenance hashes by hand, or promote a partial run.
If capture fails or inputs change, retain diagnostic artifacts and start a fresh capture after the cause is fixed.

Linux screenshots do not prove Retina quality. The exported Bazel model does not prove third-party importer behavior.
Passing display tests does not validate every future IDE build. See [testing](../../../docs/testing.md) for those boundaries.
