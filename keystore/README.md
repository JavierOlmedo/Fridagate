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

## Signing in GitHub Actions

The release workflow (`.github/workflows/release.yml`) signs the APK with this keystore. Add two repository secrets in **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `FRIDAGATE_KEYSTORE_BASE64` | The keystore, encoded in base64 |
| `FRIDAGATE_KEYSTORE_PASSWORD` | The keystore password. The key must use the same one (Android Studio's PKCS12 keystores always do) |

The key alias must be `fridagate`.

To encode the keystore and copy it to the clipboard:

```powershell
# Windows (PowerShell, from the project folder)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\keystore\fridagate-release.jks")) | Set-Clipboard
```

```bash
# macOS
base64 -i keystore/fridagate-release.jks | pbcopy
# Linux
base64 -w0 keystore/fridagate-release.jks
```

GitHub never shows a secret again once saved, and the workflow deletes the decoded keystore after the build. Keep the original `.jks` and its password in your password manager anyway: without them, users have to uninstall the app to install the next version.
