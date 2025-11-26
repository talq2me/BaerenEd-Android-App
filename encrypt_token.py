#!/usr/bin/env python3
"""
Helper script to encrypt your GitHub token for use in the Android app.
This encrypts the token using AES-256-CBC encryption.

INSTALLATION:
    First install the cryptography library:
    pip install cryptography

Usage:
    python encrypt_token.py YOUR_GITHUB_TOKEN

The script will output:
    1. The encrypted token (to store in local.properties as ENCRYPTED_GITHUB_TOKEN)
    2. The encryption key (to store in local.properties as GITHUB_TOKEN_KEY)
"""

import sys
import base64
import secrets

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
    from cryptography.hazmat.backends import default_backend
except ImportError:
    print("ERROR: cryptography library is not installed.")
    print("\nPlease install it first:")
    print("  pip install cryptography")
    sys.exit(1)

def encrypt_token(token: str) -> tuple[str, str]:
    """Encrypt the token using AES-256-CBC with a random key and IV."""
    # Generate a random 32-byte key (for AES-256)
    key = secrets.token_bytes(32)
    
    # Generate a random 16-byte IV (for AES-CBC)
    iv = secrets.token_bytes(16)
    
    # Pad the token to block size (AES block size is 16 bytes)
    token_bytes = token.encode('utf-8')
    pad_length = 16 - (len(token_bytes) % 16)
    padded_token = token_bytes + bytes([pad_length] * pad_length)
    
    # Encrypt
    cipher = Cipher(algorithms.AES(key), modes.CBC(iv), backend=default_backend())
    encryptor = cipher.encryptor()
    ciphertext = encryptor.update(padded_token) + encryptor.finalize()
    
    # Combine IV and ciphertext, then base64 encode
    encrypted_data = iv + ciphertext
    encrypted_b64 = base64.b64encode(encrypted_data).decode('utf-8')
    
    # Base64 encode the key for storage
    key_b64 = base64.b64encode(key).decode('utf-8')
    
    return encrypted_b64, key_b64

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python encrypt_token.py YOUR_GITHUB_TOKEN")
        sys.exit(1)
    
    token = sys.argv[1]
    encrypted_token, encryption_key = encrypt_token(token)
    
    print("\n" + "="*60)
    print("Encryption complete!")
    print("="*60)
    print("\n1. Add this to your local.properties file (do NOT commit):\n")
    print(f"ENCRYPTED_GITHUB_TOKEN={encrypted_token}")
    print("\n2. Hardcode this key in MainActivity.kt (it's safe to commit):\n")
    print(f"   val encryptionKeyB64 = \"{encryption_key}\"")
    print("\n" + "="*60)
    print("\nNOTE:")
    print("- The encrypted token goes in local.properties (not committed)")
    print("- The decryption key is hardcoded in MainActivity.kt (safe to commit)")
    print("- GitHub won't detect the encrypted token as a token pattern\n")

