# Keystore

This folder stores the release signing keystore for Fridagate.

The keystore file is excluded from git (see `.gitignore`) and must never be committed.

## How to generate the keystore

```bash
keytool -genkey -v \
  -keystore fridagate-release.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias fridagate
```

Or use **Android Studio → Build → Generate Signed Bundle / APK → Create new keystore**.

## Files expected here

| File | Description |
|---|---|
| `fridagate-release.jks` | Release keystore (not committed) |

## Signing credentials reference

Store your credentials securely (e.g., a password manager). You will need:

- Keystore path: `keystore/fridagate-release.jks`
- Keystore password
- Key alias: `fridagate`
- Key password
