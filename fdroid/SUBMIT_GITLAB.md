# F-Droid submission — prepared steps (GitLab, 2026-08-15)

F-Droid migrated fdroiddata to GitLab: https://gitlab.com/fdroid/fdroiddata
(The GitHub repo is a read-only mirror — pull requests are disabled there.)

## Option A — Merge Request (recommended, 5 min)
1. Sign in at https://gitlab.com with "GitHub" (OAuth) using the EA2OY account.
2. Open https://gitlab.com/fdroid/fdroiddata → Fork (into your new GitLab user).
3. In the fork, create branch `meshnavarra` from master.
4. Add the file `metadata/com.meshkachoutility.yml` (content below = the same file as
   `fdroid/metadata.yml` in this repo).
5. Commit "New App: com.meshkachoutility" and push.
6. Open the Merge Request against `fdroid/fdroiddata` master with title
   "New App: MeshNavarra Utility".
   GitLab CI will run the build test automatically on the fork.

## Option B — Request For Packaging issue (simplest, no fork)
1. Sign in at https://gitlab.com/fdroid/rfp (same GitLab account).
2. Open a new issue with this template filled:

---
Title: [RFP] MeshNavarra Utility (com.meshkachoutility)

**Application**: MeshNavarra Utility — Android tool to administer Meshtastic
nodes over USB OTG / Bluetooth LE, plus remote control of NavaTastic
repeaters. Unofficial, not affiliated with Meshtastic.

**License**: GPL-3.0-or-later
**Source**: https://github.com/EA2OY/MeshNavarra-Utility
**Tags**: v1.0.0, v1.0.1 (versionCode 2)
**Build**: plain Gradle (AGP 8.2.2, Kotlin 2.1.0, JDK 17), `gradle: yes`
(= assembleRelease). No JitPack: usb-serial-for-android 3.11.0 is vendored
in-tree (LGPL-2.1, third_party/). No network permissions, no analytics.
Fastlane metadata already in the app repo (fastlane/metadata/android).
---

## Ready-made metadata file (content for Option A)
= copy of `fdroid/metadata.yml` in this repo (URLs already fixed to
EA2OY/MeshNavarra-Utility, build block v1.0.1/versionCode 2).
