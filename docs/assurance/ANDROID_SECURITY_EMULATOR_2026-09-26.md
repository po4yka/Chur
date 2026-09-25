# Android security evidence: API 37 emulator

**Date:** 2026-09-26

**Starting revision:** `ac0078226842b27fa36fba1d4fa506ddbb9f4e3b`

**Device:** `Pixel_10_Pro_XL_API37` AVD, API 37, arm64-v8a, build `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys`

## Observed

- Set a temporary PIN with `adb shell locksettings set-pin 2468` before the instrumented run. A secure lock screen is required to generate an authentication-bound Keystore key. Cleared the PIN after testing.
- `./gradlew :apps:androidApp:connectedDebugAndroidTest --no-configuration-cache --console=plain`: **3 tests, 0 failures**. The tests create and delete a real Android Keystore slot, inspect its AES-256-GCM, per-use user-authentication and non-exportable properties, verify that `MainActivity` keeps `FLAG_SECURE` after it pauses, and verify that the export provider is not exported.
- The Keystore reported `KeyInfo.securityLevel = 0` (`SOFTWARE`) on this emulator. This result does **not** establish TEE or StrongBox protection on a physical device.
- `python3 scripts/check-backup-rules.py`: **2 rule files checked, no vault path included**.
- Local transport backup and restore on the emulator: after launching the debug app, placed `public-canary` at `files/public/backup-probe.txt` and `vault-canary` at `files/vault/backup-probe.txt` in its private data. `bmgr backupnow --monitor dev.po4yka.chur` reported success. After `pm clear dev.po4yka.chur` and `bmgr restore 1 dev.po4yka.chur`, the public file contained `public-canary`; the vault file and directory were absent. This exercised the API 37 `dataExtractionRules` cloud-backup path with a disposable local transport, not device-to-device transfer.
- The first background-window run failed: `onPause` set `FLAG_SECURE`, then the `STARTED` state collector cleared it before `onStop`. The collector now runs only while `RESUMED`; the same instrumented test passed after the change.

## Remaining release evidence

No physical Android device was available. Before claiming the Android device matrix in `SECURITY_TEST_PLAN.md` §8 complete, run this instrumented suite on the API 29 floor, a supported 16 KiB-page arm64 baseline, and a StrongBox device. Record `KeyInfo.securityLevel` on API 31+ and `isInsideSecureHardware` on API 29. Exercise convenient and strict biometric prompts, lock-screen and biometric-enrollment changes, device transfer and reinstall, recents screenshots and external display, locked WorkManager runs, picker and media edge cases, and low-storage/low-memory behavior. The emulator run did not execute those scenarios or an independent security review.
