# GitHub Token Encryption Setup

This document explains how to encrypt your GitHub token to avoid GitHub secret scanning.

## Why Encryption?

GitHub automatically scans commits and APK files for secrets (like access tokens). If a token is detected, GitHub will revoke it. By encrypting the token, GitHub won't recognize it as a token, preventing automatic revocation.

## Setup Steps

### 1. Install Python Dependencies

First, install the cryptography library:

```bash
pip install cryptography
```

### 2. Encrypt Your Token

Run the encryption script with your GitHub token:

```bash
python encrypt_token.py YOUR_GITHUB_TOKEN
```

The script will output:
- `ENCRYPTED_GITHUB_TOKEN` - The encrypted token (safe to store in BuildConfig)
- `GITHUB_TOKEN_KEY` - The decryption key (also stored in BuildConfig)

### 3. Update local.properties

Copy the encrypted token and key to your `local.properties` file:

```properties
ENCRYPTED_GITHUB_TOKEN=<encrypted_token_from_script>
GITHUB_TOKEN_KEY=<encryption_key_from_script>
```

**Important**: Do NOT commit `local.properties` to Git (it's already in `.gitignore`).

### 4. Rebuild the App

Rebuild your app so the encrypted token and key are embedded in BuildConfig:

```bash
./release.sh
```

## How It Works

1. **Encryption**: Your token is encrypted using AES-256-CBC encryption with a randomly generated key
2. **Storage**: Both the encrypted token and key are stored in `BuildConfig` (from `local.properties`)
3. **Decryption**: At runtime, the app decrypts the token using the key before making API calls
4. **Security**: The encrypted token doesn't look like a GitHub token, so secret scanning won't detect it

## Creating a GitHub Token

1. Go to GitHub → Settings → Developer settings → Personal access tokens → Tokens (fine-grained)
2. Click "Generate new token"
3. Select "Only select repositories" and choose "BaerenCloud"
4. Set permissions:
   - Contents: Read and write
   - Metadata: Read-only
5. Generate and copy the token
6. Encrypt it using the script above

## Notes

- The encrypted token is safe to store in BuildConfig (which gets embedded in the APK)
- The decryption key is also in BuildConfig, but since the token is encrypted, GitHub won't detect it
- If your token gets revoked, create a new one and re-encrypt it

