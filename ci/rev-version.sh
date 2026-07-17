#!/bin/bash

set -e

APP_GRADLE="app/build.gradle.kts"
CLIENT_GRADLE="client/build.gradle.kts"

CURRENT_VERSION=$(grep 'versionName' "${APP_GRADLE}" | head -1 | sed 's/.*"\(.*\)".*/\1/')
CURRENT_CODE=$(grep 'versionCode' "${APP_GRADLE}" | head -1 | sed 's/[^0-9]*//' | tr -d '[:space:]')

MAJOR=$(echo "${CURRENT_VERSION}" | cut -d. -f1)
MINOR=$(echo "${CURRENT_VERSION}" | cut -d. -f2)

NEXT_MINOR=$((MINOR + 1))
NEXT_VERSION="${MAJOR}.${NEXT_MINOR}"
NEXT_CODE=$((CURRENT_CODE + 1))

echo "Current version: ${CURRENT_VERSION} (code: ${CURRENT_CODE})"
echo "Next version:    ${NEXT_VERSION} (code: ${NEXT_CODE})"

for GRADLE_FILE in "${APP_GRADLE}" "${CLIENT_GRADLE}"; do
    if [ -f "${GRADLE_FILE}" ]; then
        FILE_VERSION=$(grep 'versionName' "${GRADLE_FILE}" | head -1 | sed 's/.*"\(.*\)".*/\1/')
        FILE_CODE=$(grep 'versionCode' "${GRADLE_FILE}" | head -1 | sed 's/[^0-9]*//' | tr -d '[:space:]')
        sed -i "s/versionName = \"${FILE_VERSION}\"/versionName = \"${NEXT_VERSION}\"/" "${GRADLE_FILE}"
        sed -i "s/versionCode = ${FILE_CODE}/versionCode = ${NEXT_CODE}/" "${GRADLE_FILE}"
        echo "Updated ${GRADLE_FILE}"
    fi
done

git add "${APP_GRADLE}" "${CLIENT_GRADLE}"
git commit -m "[ci skip] rev version to ${NEXT_VERSION}"
git tag -a "v${NEXT_VERSION}" -m "Release v${NEXT_VERSION}"
git push origin main
git push origin "v${NEXT_VERSION}"

echo "Version bumped to ${NEXT_VERSION} and tagged v${NEXT_VERSION}"
