# Releasing

Releases are built and published entirely by GitHub Actions. Nothing is built on a personal machine.

## Publish a release

1. Make sure `main` is green in the Actions tab.
2. Tag the commit and push the tag:

   ```bash
   git tag -a v1.2.0 -m "Vision Call v1.2.0"
   git push origin v1.2.0
   ```

The **Release** workflow then:

| Job | Publishes |
|---|---|
| `binaries` | A GitHub Release with an archive per platform (`linux-amd64`, `linux-arm64`, `darwin-arm64`, `windows-amd64`, `windows-arm64`) and `checksums.txt`. Each archive holds the program, `.env.example`, the README and that platform's installer. |
| `android` | `videocall_<tag>_android.apk`, signed with the release key, and its `.sha256` file, attached to the same release |
| `docker` | `ghcr.io/anand34577/video-call:<tag>` and `:latest` for `linux/amd64` and `linux/arm64` |

The version shown by `videocall -version`, `/api/healthz` and the Android app comes from the tag. For Android, `v1.2.3` becomes version name `1.2.3` and version code `10203`.

To rebuild an existing tag, run the **Release** workflow by hand from the Actions tab and enter the tag.

## Version numbers

Use `vMAJOR.MINOR.PATCH`:

- **PATCH** for fixes
- **MINOR** for new features that don't break existing installs
- **MAJOR** when upgrading needs manual steps

## Android signing

The APK is signed with the release key stored in the repository's Actions secrets:

| Secret | Contents |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file, base64 encoded |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

The Android job refuses to publish if the keystore secret is missing, so an unsigned APK is never released. Keep the keystore itself backed up somewhere safe: Android only accepts updates signed with the same key, so losing it means users would have to uninstall before installing a new version.

## Public downloads

The one-line installers and the Docker image are downloaded without signing in, so:

- the repository must be public, and
- the `video-call` package under the account's **Packages** must be set to public (Package settings > Change visibility).

## The wiki

The wiki pages live in `docs/wiki/` in the repository. The **Wiki** workflow copies them to the GitHub wiki whenever they change on `main`, so edit them there, not on the wiki itself.
