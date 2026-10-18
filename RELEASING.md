# Releasing RadiusRF

Release APKs must be built with the `release` build type, signed with a stable,
version-controlled-outside-of-git keystore. Never ship `assembleDebug` output as a release —
Android Studio's debug keystore is machine-specific and regenerates on reset, so any two debug
builds can carry different signing identities. Installing a new debug build over an old one (or
onto a device that already has a differently-signed build) fails with "App not installed as
package conflicts with an existing package." A stable release key is what makes updates, and
tools like Obtainium that track them by signature, work at all.

## One-time setup: generate the release keystore

Do this once, on your own machine, and never commit the resulting file:

```
keytool -genkeypair -v -keystore release.jks -alias RadiusRF \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep `release.jks` and its passwords somewhere safe (password manager, not the repo). Losing this
keystore means you can never sign an update to the existing app identity again — anyone who
already installed a prior release would have to fully uninstall before installing the new one.

## Local release builds

Create `keystore.properties` in the project root (already gitignored):

```
RELEASE_STORE_FILE=/absolute/path/to/release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=RadiusRF
RELEASE_KEY_PASSWORD=...
```

Then:

```
./gradlew assembleRelease
```

The signed APK lands at `app/build/outputs/apk/release/app-release.apk`.

If `keystore.properties` is absent and none of the `RELEASE_*` environment variables are set,
`assembleRelease` still produces an APK, but it's **unsigned** and unsuitable for distribution —
that's intentional, so a contributor without the keystore can still build locally without a hard
failure, but nobody accidentally ships an unsigned or debug-signed artifact by mistake.

## CI releases

`.github/workflows/release.yml` builds and signs `assembleRelease` from these repository secrets,
then attaches the APK to the GitHub Release for the pushed tag:

- `RELEASE_STORE_FILE_BASE64` — `base64 -i release.jks | pbcopy` (macOS) or `base64 -w0 release.jks`
  (Linux), pasted as the secret value
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Cut a release by pushing a `v*` tag (e.g. `git tag v1.2.0 && git push origin v1.2.0`).
