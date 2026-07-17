#!/bin/bash

set -e

if [ -z "$1" ]; then
    echo "Usage: $0 <version>"
    echo "Example: $0 2.0"
    exit 1
fi

NEW_VERSION="$1"

if ! echo "${NEW_VERSION}" | grep -qE '^[0-9]+\.[0-9]+$'; then
    echo "Error: Version must be in X.Y format (e.g. 2.0, 1.15)"
    exit 1
fi

APP_GRADLE="app/build.gradle.kts"
CLIENT_GRADLE="client/build.gradle.kts"

CURRENT_VERSION=$(grep 'versionName' "${APP_GRADLE}" | head -1 | sed 's/.*"\(.*\)".*/\1/')
CURRENT_CODE=$(grep 'versionCode' "${APP_GRADLE}" | head -1 | sed 's/[^0-9]*//' | tr -d '[:space:]')
NEXT_CODE=$((CURRENT_CODE + 1))

echo "Current: ${CURRENT_VERSION} (code: ${CURRENT_CODE})"
echo "New:     ${NEW_VERSION} (code: ${NEXT_CODE})"

for GRADLE_FILE in "${APP_GRADLE}" "${CLIENT_GRADLE}"; do
    if [ -f "${GRADLE_FILE}" ]; then
        FILE_VERSION=$(grep 'versionName' "${GRADLE_FILE}" | head -1 | sed 's/.*"\(.*\)".*/\1/')
        FILE_CODE=$(grep 'versionCode' "${GRADLE_FILE}" | head -1 | sed 's/[^0-9]*//' | tr -d '[:space:]')
        sed -i "s/versionName = \"${FILE_VERSION}\"/versionName = \"${NEW_VERSION}\"/" "${GRADLE_FILE}"
        sed -i "s/versionCode = ${FILE_CODE}/versionCode = ${NEXT_CODE}/" "${GRADLE_FILE}"
        echo "Updated ${GRADLE_FILE}"
    fi
done

echo ""
echo "Version updated in both modules"
echo ""
echo "Next steps:"
echo "  git add ${APP_GRADLE} ${CLIENT_GRADLE}"
echo "  git commit -m 'Bump version to ${NEW_VERSION}'"
echo "  git tag -a v${NEW_VERSION} -m 'Release v${NEW_VERSION}'"
echo "  git push origin main && git push origin v${NEW_VERSION}"
