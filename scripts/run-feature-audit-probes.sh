#!/usr/bin/env bash
# Reproduce the documented audit findings without changing production code or golden screenshots.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK_PROBE="$TASK_ROOT/app/src/test/kotlin/dev/swapnilraj/calendar/CalendarAuditProbeTest.kt"
if [[ -e "$TASK_PROBE" ]]; then
  echo "Refusing to overwrite an existing probe: $TASK_PROBE" >&2
  exit 1
fi
trap 'rm -f "$TASK_PROBE"' EXIT
cp "$TASK_ROOT/docs/audit/CalendarAuditProbeTest.kt" "$TASK_PROBE"
cd "$TASK_ROOT"
./gradlew :app:testDebugUnitTest --tests '*CalendarAuditProbeTest' \
  -Proborazzi.test.record=true -Proborazzi.test.verify=false --console=plain
