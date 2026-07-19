#!/bin/bash
#
# Run instrumented tests on Firebase Test Lab for a specific device.
#
# Usage: ./ci/device-tests.sh <device_model> <api_level>
# Example: ./ci/device-tests.sh redfin 30
#
# Prerequisites:
#   - gcloud CLI installed and authenticated
#   - Firebase project configured (FIREBASE_PROJECT env var or gcloud default)
#   - Debug APK and test APK built (see: ./gradlew assembleDebug assembleDebugAndroidTest)
#
# Environment variables:
#   FIREBASE_PROJECT  - Firebase/GCP project ID (default: gcloud config default)
#   APP_APK           - Path to app APK (default: app/build/outputs/apk/debug/app-debug.apk)
#   TEST_APK          - Path to test APK (default: app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)
#   RESULTS_DIR       - Local directory for downloaded results (default: build/ftl-results)
#   TIMEOUT           - Test timeout in seconds (default: 300)

set -euo pipefail

DEVICE_MODEL="${1:?Usage: $0 <device_model> <api_level>}"
API_LEVEL="${2:?Usage: $0 <device_model> <api_level>}"

APP_APK="${APP_APK:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${TEST_APK:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
RESULTS_DIR="${RESULTS_DIR:-build/ftl-results}"
TIMEOUT="${TIMEOUT:-300}"
RESULTS_BUCKET="${RESULTS_BUCKET:-}"
PROJECT_FLAG=""

if [ -n "${FIREBASE_PROJECT:-}" ]; then
    PROJECT_FLAG="--project=${FIREBASE_PROJECT}"
fi

# Validate APK files exist
if [ ! -f "${APP_APK}" ]; then
    echo "ERROR: App APK not found at ${APP_APK}"
    echo "Run: ./gradlew assembleDebug"
    exit 1
fi

if [ ! -f "${TEST_APK}" ]; then
    echo "ERROR: Test APK not found at ${TEST_APK}"
    echo "Run: ./gradlew assembleDebugAndroidTest"
    exit 1
fi

echo "=== Firebase Test Lab ==="
echo "Device:    ${DEVICE_MODEL}"
echo "API Level: ${API_LEVEL}"
echo "App APK:   ${APP_APK}"
echo "Test APK:  ${TEST_APK}"
echo "Timeout:   ${TIMEOUT}s"
echo "========================="

# Build gcloud command
GCLOUD_CMD=(
    gcloud firebase test android run
    --type instrumentation
    --app "${APP_APK}"
    --test "${TEST_APK}"
    --device "model=${DEVICE_MODEL},version=${API_LEVEL}"
    --timeout "${TIMEOUT}s"
    --no-auto-google-login
    --no-record-video
    --no-performance-metrics
)

if [ -n "${PROJECT_FLAG}" ]; then
    GCLOUD_CMD+=("${PROJECT_FLAG}")
fi

if [ -n "${RESULTS_BUCKET}" ]; then
    GCLOUD_CMD+=(--results-bucket "${RESULTS_BUCKET}")
fi

echo ""
echo "Running: ${GCLOUD_CMD[*]}"
echo ""

# Run tests and capture output
TEMP_OUTPUT=$(mktemp)
EXIT_CODE=0
"${GCLOUD_CMD[@]}" 2>&1 | tee "${TEMP_OUTPUT}" || EXIT_CODE=$?

# Parse results
echo ""
echo "=== Results Summary ==="

PASSED=$(grep -c "Passed" "${TEMP_OUTPUT}" 2>/dev/null || echo "0")
FAILED=$(grep -c "Failed" "${TEMP_OUTPUT}" 2>/dev/null || echo "0")
SKIPPED=$(grep -c "Skipped" "${TEMP_OUTPUT}" 2>/dev/null || echo "0")
ERRORS=$(grep -c "Error" "${TEMP_OUTPUT}" 2>/dev/null || echo "0")

echo "Device:  ${DEVICE_MODEL} (API ${API_LEVEL})"
echo "Passed:  ${PASSED}"
echo "Failed:  ${FAILED}"
echo "Skipped: ${SKIPPED}"
echo "Errors:  ${ERRORS}"

# Download results if a results directory is requested
mkdir -p "${RESULTS_DIR}"
RESULTS_URI=$(grep -oP 'https://console\.firebase\.google\.com\S+' "${TEMP_OUTPUT}" || true)
if [ -n "${RESULTS_URI}" ]; then
    echo "Console: ${RESULTS_URI}"
    echo "${RESULTS_URI}" > "${RESULTS_DIR}/console-url.txt"
fi

# Write machine-readable summary
cat > "${RESULTS_DIR}/summary.json" <<EOF
{
  "device_model": "${DEVICE_MODEL}",
  "api_level": ${API_LEVEL},
  "passed": ${PASSED},
  "failed": ${FAILED},
  "skipped": ${SKIPPED},
  "errors": ${ERRORS},
  "exit_code": ${EXIT_CODE},
  "status": "$([ ${EXIT_CODE} -eq 0 ] && echo "PASS" || echo "FAIL")"
}
EOF

rm -f "${TEMP_OUTPUT}"

echo ""
if [ ${EXIT_CODE} -eq 0 ]; then
    echo "RESULT: PASS"
else
    echo "RESULT: FAIL (exit code: ${EXIT_CODE})"
fi

exit ${EXIT_CODE}
