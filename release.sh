#!/bin/bash
set -e

### --- CONFIG --- ###
KEYSTORE_PATH="C:/Users/talqu/keystore1"   # your keystore
KEY_ALIAS="key1"

VERSION_JSON="app/src/main/assets/config/version.json"
GRADLE_FILE="app/build.gradle.kts"
APK_SOURCE="app/build/outputs/apk/release/app-release.apk"

# This is the folder inside THE SAME repo that is served by GitHub Pages
PAGES_APK_PATH="app/release/app-release.apk"

### --- READ CURRENT VERSION --- ###
CURRENT_VERSION=$(grep "versionCode" -i "$GRADLE_FILE" | grep -o "[0-9]*")
NEW_VERSION=$((CURRENT_VERSION + 1))

echo "Current version: $CURRENT_VERSION"
echo "New version: $NEW_VERSION"

### --- PRE-BUILD CHECK --- ###
echo "Running pre-build check to verify compilation..."
echo "This ensures the build will succeed before we update version numbers."

./gradlew clean compileReleaseKotlin

if [ $? -ne 0 ]; then
    echo "ERROR: Pre-build check failed! Fix compilation errors before releasing."
    exit 1
fi

echo "Pre-build check passed! Proceeding with release..."

### --- GET PASSWORD --- ###
# Use environment variable if set, otherwise prompt
# In Git Bash/Windows, you may need to export the variable in the same session:
#   export KEYSTORE_PASSWORD='your_password'
#   ./release.sh

if [ -n "${KEYSTORE_PASSWORD:-}" ]; then
    STOREPASS="$KEYSTORE_PASSWORD"
    echo "Using password from KEYSTORE_PASSWORD environment variable"
else
    echo ""
    echo "Note: To skip password prompt, set KEYSTORE_PASSWORD environment variable:"
    echo "  export KEYSTORE_PASSWORD='your_password'"
    echo "  ./release.sh"
    echo ""
    echo -n "Enter keystore password: "
    read -s STOREPASS
    echo ""
fi

### --- UPDATE build.gradle --- ###
sed -i "s/versionCode\s*=.*/versionCode = $NEW_VERSION/" "$GRADLE_FILE"
sed -i "s/versionName\s*=.*/versionName = \"$NEW_VERSION\"/" "$GRADLE_FILE"


### --- UPDATE version.json --- ###
# Replace "latestVersionCode": X with new version
sed -i "s/\"latestVersionCode\":.*/\"latestVersionCode\": $NEW_VERSION,/" "$VERSION_JSON"

### --- BUILD SIGNED APK --- ###
echo "Building signed APK..."

./gradlew clean assembleRelease -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
  -Pandroid.injected.signing.store.password="$STOREPASS" \
  -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
  -Pandroid.injected.signing.key.password="$STOREPASS"

echo "APK built."

### --- COPY APK TO GITHUB PAGES FOLDER --- ###
cp "$APK_SOURCE" "$PAGES_APK_PATH"

### --- GIT COMMIT + TAG + PUSH --- ###
# Add all files except local.properties (which contains secrets and should not be committed)
git add app/build.gradle.kts
git add app/release/app-release.apk
git add app/src/main/assets/config/version.json
git add app/src/main/java/com/talq2me/baerened/MainActivity.kt
# Explicitly exclude local.properties if it was tracked before
git restore --staged local.properties 2>/dev/null || true

git commit -m "Release version $NEW_VERSION"
git tag "v$NEW_VERSION"

git push
git push --tags

echo "Release v$NEW_VERSION complete!"
echo "APK deployed to GitHub Pages."
