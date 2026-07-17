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

GRADLE_FILE="app/build.gradle.kts"

CURRENT_VERSION=$(grep 'versionName' "${GRADLE_FILE}" | head -1 | sed 's/.*"\(.*\)".*/\1/')
CURRENT_CODE=$(grep 'versionCode' "${GRADLE_FILE}" | head -1 | sed 's/[^0-9]*//' | tr -d '[:space:]')
NEXT_CODE=$((CURRENT_CODE + 1))

echo "Current: ${CURRENT_VERSION} (code: ${CURRENT_CODE})"
echo "New:     ${NEW_VERSION} (code: ${NEXT_CODE})"

sed -i "s/versionName = \"${CURRENT_VERSION}\"/versionName = \"${NEW_VERSION}\"/" "${GRADLE_FILE}"
sed -i "s/versionCode = ${CURRENT_CODE}/versionCode = ${NEXT_CODE}/" "${GRADLE_FILE}"

echo ""
echo "Version updated in ${GRADLE_FILE}"
echo ""
echo "Next steps:"
echo "  git add ${GRADLE_FILE}"
echo "  git commit -m 'Bump version to ${NEW_VERSION}'"
echo "  git tag -a v${NEW_VERSION} -m 'Release v${NEW_VERSION}'"
echo "  git push origin main && git push origin v${NEW_VERSION}"
