# CLAUDE.md

## What this is

An Android app bridging a Proxmark to a phone over USB-OTG, Bluetooth classic
(Blueshark) or BLE (Proxmark5 BWM), shipping a cross-compiled Proxmark3 client,
a firmware flasher, and Termux integration.

## Sibling checkouts

Several build scripts expect these next to this repo (override with `PM3_SRC`):

| Path | Repo | Used for |
|---|---|---|
| `../proxmark3` | `RfidResearchGroup/proxmark3` | Client source, firmware source, udev rules (USB IDs), `doc/termux_notes.md` |
| `../Proxmark5_BWM_esp32` | `RfidResearchGroup/Proxmark5_BWM_esp32` | BWM BLE UUIDs and GATT behaviour |
| `../node-proxmark3` | `nemanjan00/node-proxmark3` | Reference for the JSON-line RPC design |
| `../termux-app`, `../termux-api` | `termux/*` | `RUN_COMMAND` contract, plugin constraints |

## The central design decision

The client already speaks `tcp:host:port`. So the app **never** patches the
client to understand Android transports — it terminates USB/BT/BLE itself and
pumps bytes to a loopback TCP socket the stock client connects to.

Consequences worth remembering before changing anything:

- The client is built with `SKIPBT=1`; it has no transport of its own here.
- One TCP socket serves the in-app console, the flasher, and Termux.
- `TcpBridge` accepts a backlog of 1 deliberately. The Proxmark protocol is
  framed and stateful; two concurrent clients interleave frames and corrupt
  both sessions.
- The socket binds `127.0.0.1` only. The protocol is unauthenticated.

## Hard-won build facts

Do not "fix" these without reading why:

- **`SKIPPTHREAD=1` is mandatory.** bionic folds pthread into libc; there is no
  `libpthread.so` and the link fails without it.
- **`EMBED_BZIP2` / `EMBED_LZ4`** build those deps for the target. They are
  `ExternalProject`s with no ninja rule tying them to the link, so
  `build-pm3.sh` builds those targets explicitly first.
- **One upstream patch** (`native/patches/0001-*`): `pm3_luawrap.c`'s
  `bases_count` is consumed only by an `assert`, so `-DNDEBUG` + `-Werror`
  fails. Not Android-specific. Everything else in the tree compiles clean
  against clang/bionic — one warning in ~230 files — so if a build starts
  producing warnings, that is new and worth reading, not suppressing.
- **`firmware/matrix.conf` is `|` separated, not tab.** Tab is IFS whitespace,
  so bash `read` collapses runs of it and every field after an empty one
  silently shifts.
- **Firmware variants must build serially.** The pm3 Makefile caches `PLATFORM`
  in `.Makefile.options.cache` at the tree root and hard-errors on a change
  without a clean.

## Hard-won Android facts

- The client ships as `jniLibs/<abi>/libproxmark3.so`. Since API 29 `exec()` is
  only permitted from `nativeLibraryDir`, and the packager only extracts files
  matching `lib*.so`. It is an executable, not a library — nothing loads it.
- `extractNativeLibs=true` and `useLegacyPackaging = true` are both required:
  the binary must be a real file on disk.
- `nativeLibraryDir` is read-only, so resources unpack to `$HOME/.proxmark3`,
  which the client already searches (`PM3_USER_DIRECTORY`, pm3's
  `include/common.h`). The app sets `HOME`; no client patch needed.
- **This cannot be a real Termux plugin.** Plugins declare
  `android:sharedUserId="com.termux"` and must be signed with the same key as
  the installed Termux, so a third-party one will not install beside the
  F-Droid build. Integration goes through the public `RUN_COMMAND` service.

## Domain rules that protect hardware

- **Flashing is USB only**, enforced in `Transport.supportsFlashing`,
  `MainViewModel.flash` and the Flash screen. The bootloader does not bring up
  the Blueshark or BWM radio; a wireless flash drops mid-write. Keep all three
  checks.
- **`BTADDON` / `BWM` firmware options** are what make wireless work at all. A
  user whose Blueshark pairs but times out almost always has firmware built
  without `BTADDON`.
- Writing the bootloader is offered but defaults off. A failed bootloader write
  needs JTAG to recover.

## Driving the client

`Pm3Session` talks to a Lua shim (`app/src/main/assets/pm3_rpc.lua`) running
*inside* the client, because the client has no machine-readable mode and no
end-of-command signal. The shim reads one JSON object per line, runs
`core.console()`, and emits a `command_end` sentinel.

A line only counts as control if it parses as JSON **and** carries one of the
shim's type tags — command output can legitimately be JSON.

Ported from node-proxmark3's `interpreter.lua`, using the `dkjson` already in
the client's `lualibs` rather than a vendored copy.

## Untested

No Proxmark hardware and no emulator were available while this was written.
The cross-compile, the APK build and the firmware matrix are all verified;
**everything that requires a device is not**. Treat first-run behaviour of the
transports, the flasher and the Termux wrapper as unproven.
