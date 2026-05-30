# Release Signing Setup

Every release build produced by the GitHub Actions workflow is **required to be signed**. The workflow will fail with a clear error if any of the four signing secrets are missing, ensuring no unsigned APK is ever published by accident.

---

## Required GitHub Secrets

| Secret name        | Description                                              |
|--------------------|----------------------------------------------------------|
| `KEYSTORE_BASE64`  | Your `.jks` / `.keystore` file, base64-encoded           |
| `KEYSTORE_PASSWORD`| Password for the keystore file                           |
| `KEY_ALIAS`        | Alias of the signing key inside the keystore             |
| `KEY_PASSWORD`     | Password for the signing key (may equal KEYSTORE_PASSWORD)|

---

## Step 1 — Generate a keystore (one-time)

If you don't already have a keystore, create one with the Android `keytool` (ships with any JDK):

```bash
keytool -genkeypair \
  -v \
  -keystore xelo-release.jks \
  -alias xelo-key \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

You will be prompted for:
- A **keystore password** — save this; it becomes `KEYSTORE_PASSWORD`
- Owner details (name, org, country)
- A **key password** — save this; it becomes `KEY_PASSWORD`

The `alias` you pass to `-alias` becomes `KEY_ALIAS`.

> **Keep the generated `.jks` file safe.** Losing it means you can never publish an update to the same app on Google Play. Back it up in a secure location outside this repository.

---

## Step 2 — Base64-encode the keystore

```bash
# macOS / Linux
base64 -i xelo-release.jks | tr -d '\n'

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("xelo-release.jks"))
```

Copy the entire output — this becomes `KEYSTORE_BASE64`.

---

## Step 3 — Add secrets to GitHub

1. Go to your repository on GitHub.
2. Navigate to **Settings → Secrets and variables → Actions**.
3. Click **New repository secret** and add each of the four secrets:

| Secret name         | Value                                        |
|---------------------|----------------------------------------------|
| `KEYSTORE_BASE64`   | Full base64 string from Step 2               |
| `KEYSTORE_PASSWORD` | Keystore password chosen in Step 1           |
| `KEY_ALIAS`         | Alias used in Step 1 (e.g. `xelo-key`)       |
| `KEY_PASSWORD`      | Key password chosen in Step 1                |

---

## How the workflow uses these secrets

1. **Verification** — The workflow checks that all four secrets are non-empty at the start of the job. If any are missing it exits with an error and prints which secrets need to be added.
2. **Decode** — `KEYSTORE_BASE64` is decoded back to a `.jks` file in the runner's workspace.
3. **Build & sign** — `assembleRelease` is invoked with the four Gradle signing properties, so `zipalign` and `apksigner` run automatically as part of the release variant.
4. **Verify** — `apksigner verify` confirms the APK carries a valid signature before it is uploaded.

---

## Troubleshooting

| Symptom | Likely cause |
|---------|-------------|
| `The following required signing secrets are not set` | One or more secrets are missing from the repo settings — see Step 3 above. |
| `Invalid keystore format` | The base64 string is corrupted or has extra whitespace. Re-encode and update `KEYSTORE_BASE64`. |
| `UnrecoverableKeyException: Cannot recover key` | `KEY_PASSWORD` is wrong for the alias inside the keystore. |
| `KeyStore exception: Keystore was tampered with` | `KEYSTORE_PASSWORD` is wrong. |
| Google Play rejects the APK after an update | You used a different keystore than the one used for the first upload. Use the original keystore — there is no recovery path on Google Play. |
