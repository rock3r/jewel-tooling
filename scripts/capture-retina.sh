#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
capture_id="$(python3 -c 'import uuid; print(uuid.uuid4())')"
python3 scripts/promote-captures.py --capture-id "$capture_id" --begin
./gradlew :e2e:driver-plugin:exportFixtureSdk
python3 scripts/prepare-bazel-fixture.py
./gradlew -p fixtures/standalone test ktfmtCheck detekt -PcaptureId="$capture_id" -Pretina=true
./gradlew :e2e:runner:test --tests '*JewelTargetsTest*' -PcaptureId="$capture_id" -Pretina=true
python3 scripts/promote-captures.py --capture-id "$capture_id"
