# Proxmark Bridge

An Android app that connects a Proxmark to your phone over **USB-OTG**,
**Bluetooth classic** (Blueshark) or **BLE** (Proxmark5 BWM), ships a
**compiled Proxmark3 client**, can **flash firmware**, and exposes the device
to **Termux** so `pm3` works in a shell.

It replaces the paid third-party bridge apps that upstream's
[termux notes](https://github.com/RfidResearchGroup/proxmark3/blob/master/doc/termux_notes.md)
currently point people at.

## How it works

```
   Termux:  pm3  ──┐
                   ├──► tcp:127.0.0.1:18888 ──► [ bridge ] ──► USB-OTG ──► Proxmark3 / 5
   App console  ───┤                                     ├──► BT SPP  ──► Blueshark
   App flasher  ───┘                                     └──► BLE     ──► PM5 BWM
```

The Proxmark client already speaks `tcp:host:port` natively. Rather than
teaching it about Android USB or BLE, the app terminates the transport itself
and pumps it to a loopback TCP socket. One socket serves the in-app console,
the flasher, and anything you run in Termux — and the client stays stock.

The socket binds to `127.0.0.1` only. The Proxmark protocol is
unauthenticated, so exposing it on the network would hand the reader to anyone
on the same Wi-Fi.

## What CI produces

| Trigger | Jobs that run | Output |
|---|---|---|
| push to the default branch | `client`, `apk`, `firmware`, `nightly` | rolling `nightly` prerelease, republished |
| push to any other branch, or a PR | `client`, `apk`, `firmware` | APKs and firmware as job artifacts |
| 04:15 UTC daily | + `nightly` | rolling `nightly` prerelease |
| push a `v*` tag | + `release` | a published release |

Every commit on the default branch republishes `nightly`, so the newest signed
APKs are always one click away without tagging anything. It is marked a
prerelease and never becomes "Latest", so it cannot displace a tagged release
— which matters because the in-app firmware download resolves
`/releases/latest/download`.

`release` showing as *skipped* on an ordinary push is expected — it is gated
on a tag, not broken.

Published releases carry only the **signed release** APKs; the debug builds
stay as job artifacts, since publishing debug-key APKs beside the real ones
just invites installing the wrong one. If the signing secrets are missing the
publish fails rather than uploading unsigned APKs, which cannot be installed
anyway.

To cut an actual release:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

## Repository layout

| Path | What it is |
|---|---|
| `native/build-pm3.sh` | Cross-compiles the client for Android (arm64-v8a, armeabi-v7a) |
| `native/patches/` | Portability patches applied to the pm3 tree at build time |
| `firmware/matrix.conf` | Firmware build matrix: platform × options |
| `firmware/build-matrix.sh` | Builds every variant, emits a `manifest.json` |
| `app/` | The Android app (Kotlin, Compose) |
| `.github/workflows/build.yml` | CI: client per ABI → APK, firmware matrix, tagged release |

## Building

Needs the Android SDK + NDK, a JDK 17, and `arm-none-eabi-gcc` for firmware.

```sh
git clone https://github.com/RfidResearchGroup/proxmark3 ../proxmark3

# 1. the client, staged straight into the app
PM3_SRC=../proxmark3 ./native/build-pm3.sh

# 2. the APK
./gradlew :app:assembleDebug

# 3. device firmware (optional; needs arm-none-eabi-gcc)
PM3_SRC=../proxmark3 ./firmware/build-matrix.sh
```

## Firmware matrix

`firmware/matrix.conf` defines ten variants across `PM3RDV4`, `PM3GENERIC`
(Easy / RDV1 / RDV2), `PM3ICOPYX`, `PM3ULTIMATE` and `PM5`.

Two build options decide whether wireless works at all:

- **`BTADDON`** — required for the Blueshark on a PM3. Without it the module
  powers up, pairs, and the blue LED goes solid, but the client then times out
  with `cannot communicate with the Proxmark3`. This is the single most common
  wireless failure.
- **`BWM`** — required for the Proxmark5 to forward serial to the Battery
  Wireless Module.

## Firmware delivery

Images are **not** bundled in the APK — that would be several megabytes most
users never touch, on a release cadence that should not be tied to the app's.
The Flash tab downloads them from the CI release instead, and verifies each
one against the manifest's sha256 before it becomes selectable. A truncated
download still parses as an ELF, so the check is the difference between a
failed download and a bricked reader.

Point it at a different release with `FirmwareRepository(context, releaseBase)`.

## Flashing

**USB only.** The app refuses to flash over Bluetooth, at three separate
layers. Flashing reboots the device into its bootloader, which does not bring
up the Blueshark or BWM radio — a wireless link drops mid-write and leaves the
firmware half-applied. Upstream's notes say the same.

If a flash is interrupted the device stays in bootloader mode and you can
simply retry; the bootloader itself is not rewritten unless you explicitly ask
for it.

## Release signing

The signing key **is** the app's identity on Android. Once someone installs an
APK signed with it, every later update must carry the same key — there is no
migration path except uninstall and reinstall, losing app data. Generate it
once, back it up, never commit it.

```sh
./tools/make-release-key.sh        # writes secrets/ (gitignored)
./tools/push-signing-secrets.sh    # uploads it to GitHub Actions secrets
```

The first prints the certificate fingerprint — that part is public, and it is
what users compare to check an APK really came from you.

### Verifying a download

Every published APK is signed by this certificate:

```
SHA-256  69:B1:6F:A0:E2:DD:13:27:10:5C:CB:B0:A0:C8:20:65:19:06:8C:50:9F:3F:8B:5C:4A:B7:65:7C:2D:AE:0E:94
```

Check a downloaded APK against it:

```sh
apksigner verify --print-certs app-arm64-v8a-release.apk
```

CI pins the same value, so a build signed by any other key fails rather than
publishing. A missing secret and a *swapped* one are different failures: the
first yields an unsigned APK, the second a perfectly valid signature from the
wrong key, which only a pinned fingerprint catches.

Local release builds pick `secrets/release.properties` up automatically. CI
reconstructs that same file from three repository secrets (`KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_PASSWORD`) plus a `KEY_ALIAS` **variable** — one
signing path, so CI and local cannot drift. The alias is deliberately not a
secret: GitHub masks a secret's value everywhere, and an alias of `release`
turns every logged path into `app/build/outputs/apk/***/app-***.apk`.

With no keystore configured it
builds unsigned rather than failing, so anyone can still build and diff the
output. When they *are* configured, CI verifies the signature and fails if the
APK came out unsigned — otherwise a misspelled secret ships a broken release
that only a user's failed install would reveal.

Signatures are v2+v3 only. minSdk is 26, so every target supports them, and v1
is the weaker scheme.

## Termux

This is **not** a Termux plugin in the strict sense, and it cannot be. A real
plugin declares `android:sharedUserId="com.termux"` (see `termux-api`'s
manifest) and must therefore be signed with the *same key* as the installed
Termux — so no third-party plugin can be installed next to the F-Droid build.

Instead the app uses Termux's public `RUN_COMMAND` service to drop a `pm3`
wrapper into `$PREFIX/bin`. After that, `pm3` in any Termux session connects
through the bridge. The wrapper prefers a `pkg install proxmark3` client if you
have one, and otherwise execs the copy bundled in this APK.

The app is fully usable without Termux — the Console tab runs the bundled
client directly.

## Notes on the Android port

- The client is packaged as `jniLibs/<abi>/libproxmark3.so`, not as an asset.
  Since API 29, Android only permits `exec()` from an app's
  `nativeLibraryDir`, and the packager only extracts files matching `lib*.so`.
- `extractNativeLibs=true` is required: the binary must be a real file on
  disk, not mapped out of the APK.
- `nativeLibraryDir` is read-only, so the client's dictionaries and scripts
  unpack to `$HOME/.proxmark3` — a path the client already searches
  (`PM3_USER_DIRECTORY` in the pm3 tree's `include/common.h`) — and the app
  sets `HOME` accordingly.
- `SKIPPTHREAD=1` is needed to link: bionic folds pthread into libc, so there
  is no `libpthread.so`.

## Credits

- [RfidResearchGroup/proxmark3](https://github.com/RfidResearchGroup/proxmark3) — the client and firmware
- [nemanjan00/node-proxmark3](https://github.com/nemanjan00/node-proxmark3) — the JSON-line RPC design the in-app session is ported from
- [mik3y/usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) — USB CDC-ACM
