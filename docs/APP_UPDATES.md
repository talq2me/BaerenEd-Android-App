# App auto-updates

BaerenLock (device owner) silently installs updates for BaerenEd and itself. BaerenEd falls back to a user prompt only when BaerenLock is not the device owner.

## Manifest URLs (polled by BaerenLock)

| App | URL |
|-----|-----|
| BaerenEd | `https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/config/version.json` |
| BaerenLock | `https://raw.githubusercontent.com/talq2me/BaerenLock/main/release-config/version.json` |

BaerenEd reads the same BaerenEd manifest URL on startup (unless BaerenLock manages updates).

## Manifest format

```json
{
    "package": "com.talq2me.baerened",
    "latestVersionCode": 164,
    "apkUrl": "https://github.com/talq2me/BaerenEd-Android-App/releases/download/v164/app-release.apk"
}
```

`apkUrl` must be a **public** download URL. GitHub Release assets use:

`https://github.com/talq2me/<repo>/releases/download/<tag>/app-release.apk`

where `<tag>` matches your release tag (e.g. `v164`). Do not use GitHub Actions workflow artifacts; they are not public and expire.

## CI release flow

1. Bump `versionCode` in `app/build.gradle.kts` (or run `release.sh`).
2. Commit, tag `v<versionCode>`, push tag.
3. GitHub Actions (`.github/workflows/release.yml`) builds a signed APK, creates a GitHub Release, and commits the updated manifest `apkUrl` to `main`.

### Required GitHub secrets (both repos)

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded signing keystore (see below) |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias — must be `key1` (required; empty alias fails CI) |
| `KEY_PASSWORD` | Key password (optional if same as keystore password) |

Encode the keystore in **Git Bash** (one line, no line breaks):

```bash
base64 -w0 "C:/Users/talqu/keystore1"
```

Do **not** use `certutil -encode` (adds PEM headers). The file is PKCS12 format; CI decodes it as `.p12` with `store.type=PKCS12`.

## Manual device test checklist

1. Provision tablet with BaerenLock as device owner.
2. Install BaerenEd and BaerenLock builds **below** the versions in the manifests.
3. Push a new tag and wait for CI to finish (Release + manifest commit).
4. Confirm BaerenLock `GuardianForegroundService` logs show update check (or reboot tablet).
5. BaerenEd should update silently without a dialog; BaerenLock self-update restarts the launcher.
6. Offline tablet: no crash; update retries on next scheduled check.
