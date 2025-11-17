#!/bin/bash
set -e

### --- CONFIG --- ###
KEYSTORE_PATH="C:/Users/talqu/keystore1"   # your keystore
KEY_ALIAS="key1"

VERSION_JSON="app/src/main/assets/config/version.json"
GRADLE_FILE="app/build.gradle"
APK_SOURCE="app/release/app-release.apk"

# This is the folder inside THE SAME repo that is served by GitHub Pages
PAGES_APK_PATH="app/release/app-release.apk"

### --- GET PASSWORD --- ###
echo -n "Enter keystore password: "
read -s STOREPASS
echo ""

### --- READ CURRENT VERSION --- ###
CURRENT_VERSION=$(grep "versionCode" -i "$GRADLE_FILE" | grep -o "[0-9]*")
NEW_VERSION=$((CURRENT_VERSION + 1))

echo "Current version: $CURRENT_VERSION"
echo "New version: $NEW_VERSION"

### --- UPDATE build.gradle --- ###
sed -i "s/versionCode .*/versionCode $NEW_VERSION/" "$GRADLE_FILE"

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
git add "$GRADLE_FILE" "$VERSION_JSON" "$PAGES_APK_PATH"

git commit -m "Release version $NEW_VERSION"
git tag "v$NEW_VERSION"

git push
git push --tags

echo "Release v$NEW_VERSION complete!"
echo "APK deployed to GitHub Pages."
