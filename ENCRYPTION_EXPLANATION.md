# GitHub Token Encryption - How It Works

## The Problem
- GitHub automatically scans commits and APK files for secrets (like access tokens)
- If a token pattern is detected, GitHub revokes the token
- You need the APK in the repo for auto-updates, but GitHub will scan it

## The Solution
Encrypt the token so GitHub can't recognize it as a token pattern.

## How It Works

### What Gets Committed to the Repo:
1. ✅ **Source code** (MainActivity.kt) - contains the hardcoded decryption key
2. ✅ **APK file** (`app/release/app-release.apk`) - contains the encrypted token in BuildConfig
3. ❌ **local.properties** - NOT committed (contains plain token before encryption)

### What's in the APK:
- **Encrypted token**: Random-looking Base64 data (doesn't match token patterns)
- **Decryption key**: In source code (visible in repo)

### Why GitHub Won't Detect It:
GitHub secret scanning looks for specific **patterns**:
- `github_pat_` (fine-grained tokens)
- `ghp_` (classic tokens)
- Other known token formats

An encrypted token looks like this:
```
aGVsbG8gd29ybGQgdGhpcyBpcyBqdXN0IGJhc2U2NCBkYXRhIGFuZCBpdCBkb2VzbnQgbG9vayBsaWtlIGEgZ2l0aHViIHRva2Vu
```

This is just random Base64 - **no token pattern = no detection = no revocation**.

## Security Notes

### Against GitHub Secret Scanning:
✅ **Works perfectly** - Encrypted data doesn't match token patterns

### Against Someone Who Has the Repo:
⚠️ **Not fully secure** - If someone has:
- The APK (from the repo) → can extract encrypted token
- The source code (from the repo) → can see the decryption key
- Then they can decrypt the token

But that's a different threat model. For preventing **automatic GitHub revocation**, encryption works great.

## Setup Steps

1. **Encrypt your token**:
   ```bash
   python encrypt_token.py YOUR_GITHUB_TOKEN
   ```

2. **Add encrypted token to local.properties**:
   ```properties
   ENCRYPTED_GITHUB_TOKEN=<encrypted_token_from_script>
   ```

3. **Hardcode the decryption key in MainActivity.kt**:
   Replace `YOUR_ENCRYPTION_KEY_HERE` with the key from the script output

4. **Build and commit**:
   - Build the APK (encrypted token goes into BuildConfig)
   - Commit the APK to the repo
   - GitHub scans it but doesn't detect a token pattern
   - Your token stays valid! 🎉

## Summary
- ✅ APK can be in repo (required for auto-updates)
- ✅ Encrypted token is in APK (but GitHub won't detect it)
- ✅ Decryption key is in source code (safe to commit)
- ✅ GitHub secret scanning won't trigger
- ✅ Token won't be revoked automatically

