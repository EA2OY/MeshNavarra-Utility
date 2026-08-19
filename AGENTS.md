# MeshKachoUtility — Developer Context

> **ECOSISTEMA**: esta app y el **Firmware NavaTastic** (Navarrico 4.3, repo hermano en la misma organización de
> GitHub) **funcionan en conjunto y se complementan**: MeshNavarra es la interfaz de gestión (USB OTG/BLE) de los
> repetidores que corren NavaTastic (canal Navadmin + `/nava` por DM PKI, resiliencia energética, protección de
> Flash). **Para sacar pleno partido del ecosistema se usan ambos proyectos juntos**: el firmware en los nodos de
> la malla y esta app como herramienta de operación diaria. Tratar al proyecto hermano como complementario, no
> como competidor. Referencia del firmware: `C:\Firmware Navarrico 4.3\` (solo lectura).

Android app (Kotlin) that manages Meshtastic nodes over USB OTG or Bluetooth. Unofficial fork.
User-facing brand: **MeshNavarra Utility** (`app_name`, manifest `android:label`, disclaimer, manual).
Technical identifiers stay `com.meshkachoutility` / `MeshKachoUtilityApp` / `Theme.MeshKachoUtility` (internal, not shown).
Most logic lives in a single file: `app/src/main/java/com/meshkachoutility/MainActivity.kt`.

> **Start here**: full agent brain + handover + subnotes live in `Cerebro_MeshKachoUtility\cerebro.md`
> (agent entry guide at repo root: `GUIA_AGENTE.md`). Firmware Navarrico context (the node firmware this
> app controls): `C:\Firmware Navarrico 4.3\Contexto y Manuales\`.

## Build / run
- JDK17 is bundled: `jdk-17/jdk-17.0.10+7` (set `JAVA_HOME` to it).
- Build: `gradlew.bat assembleDebug --console=plain`
- Test device: `adb -s 192.168.3.206:5555` (WiFi ADB).
- Install + launch: `adb -s 192.168.3.206:5555 install -r app/build/outputs/apk/debug/app-debug.apk`, then `adb -s 192.168.3.206:5555 shell am force-stop com.meshkachoutility` and `adb ... shell monkey -p com.meshkachoutility -c android.intent.category.LAUNCHER 1`.

## Strings
- `app/src/main/res/values/strings.xml` (English) and `values-es/strings.xml` (Spanish). The app is bilingual; every user-visible string must exist in both files.

## Bottom tabs (7)
`TabLayout` (`bottomTabs`), `tabMode="scrollable"`, wrapped in `tabBarWrap` with edge arrows
(`tabHintLeft`/`tabHintRight`). The arrows are ALWAYS visible (dimmed when there is nothing more in that
direction, pulsing while more tabs are off-screen) and tapping one moves to the previous/next tab.
Content area (`tabContent`) supports horizontal swipe to change tab (`setupTabSwipe`). Panels are toggled
by visibility in `setupBottomTabs`.
Order: Good Practices(0), Commands(1), Administration(2), NavaTastic CLI(3), Chat(4), Nodes(5), Log(6).
The NavaTastic tab label is `NavaTastic CLI`; every time the tab is opened a scrollable intro popup
(`showNavaTasticIntro`) reminds that the target node must run the custom Navarrico/NavaTastic firmware and
summarizes what it is + improvements over stock Meshtastic.
Per-tab explainer: `showTabExplainer(position)` shows a scrollable "what is this tab" popup on entry, up to
8 times per tab (persisted as `tab_explain_<pos>` in the `meshkacho` prefs). The very first automatic
selection at app start is skipped (`initialTabSelected`). NavaTastic (3) is excluded (its intro covers it).
The Good Practices tab (0) has an in-section help button (`bpHelpButton`, "Why these settings?") that opens
`showGoodPracticesInfo()` — an extensive explanation of the ETSI EN 300 220 868 MHz sub-band duty-cycle
limits and why beacons should be minimized (NodeInfo/GPS every 72 h). Content: `tab_info_body_gp`.

## NavaTastic tab (richest feature)
Remote control for a NavaTastic LoRa repeater. Implemented in `setupNavaTastic` + helpers in MainActivity.

Data model (`NavaCmd`): `(label, cmd, argType, mode, desc, options=[], warn="")`.
- `argType`: `none | text | text2 | number | nodeid | select | onoff`.
- `mode`: default/preferred route — `"ch"` = read-only command on the **Navadmin channel**; `"dm"` = control command sent as **PKI-encrypted DM** to the target node.
- **Route selector** (`navaRouteToggle`, "Send via"): the user chooses **Navadmin vs DM per send**; **DM is the default**. Both options are always enabled. Under the Navadmin route, control commands (`mode == "dm"`) are shown in RED in the command dropdown (`NavaCommandAdapter`); trying to pick one shows `nava_cmd_not_allowed_route` popup and reverts to an allowed command (`revertNavaSelection`). Switching to Navadmin while a control command is selected auto-reverts. `sendNavaCommand` validates first (`navaValidationError`): no command chosen, route/command mismatch, missing DM target, missing/invalid args → popup (`showNavaError`). Preview (`updateNavaPreview`) reflects the real route.
- `desc`: short what-it-does (from the NavaTastic manual), shown inline (`navaDescText`) and in the "?" popup (`navaHelpButton`).
- `warn`: non-empty ⇒ the command is **destructive/risky**; sending is blocked behind a safety dialog.

Command catalog lives inline in `navaCategories` (Diagnostics, Blocks, Favorites, Configuration, Maintenance, Power, Transmission, Utilities).

### Safety confirmation (critical)
`confirmDangerousNava` shows a popup with the specific risk (`warn`) + generic body; the Send button stays
**disabled until the user types `CONFIRMAR`** (string `nava_confirm_word`, case-insensitive). Then `doSendNava` runs.
Dangerous commands today: `set_chem`, `set_vbat`, `set_vwake`, `storm`, `storm test1`, `storm test2`,
`txoff`, `ble`, `db_purge`, `db_clear`, `factory_reset`, `full_reset`, `wipe`, `ch_del`, `ch_reset`,
`ign clear`, `keys_clear`. A wrong battery/sleep/wake/channel value can leave the node unusable until
physical recovery. UX: dangerous commands are labelled `⚠` in the command spinner and the preview line turns red.

### UI feedback
- `navaPreviewText` shows the exact `/nava ...` line that will be sent, updating live with target/args.
- Dropdowns (`navaCategorySpinner`, `navaCommandSpinner`, `navaOptionSpinner`) are wrapped in outlined
  MaterialCardView "boxes" with a chevron-down drawable so they read as dropdowns.
- Conversation is split in **two console-style sections** — NAVADMIN and DM PRIVATE — with coloured headers
  and monospace lines (`[HH:mm] » me: ...` / `« NODE: ...`). `NavaMsg` carries a `route` field
  (`"ch"`/`"dm"`); history `getExternalFilesDir()/navatastic/history.json` now stores it (defaults to `"ch"`
  for old entries). Capture is done in `maybeCaptureNavaMessage`.
- The top "?" help dialog has a button (`openNavaManual`) that opens the bundled PDF
  `assets/Manual_NavaTastic.pdf` via FileProvider (`com.meshkachoutility.fileprovider`,
  `res/xml/file_paths.xml` cache-path). The PDF was copied from `C:\Firmware Navarrico 4.3\Contexto y Manuales\Manual_NavaTastic.pdf`.

### Navadmin channel
Read-only responses arrive on the Navadmin channel (name "Navadmin", PSK `AQ==`). Detection is by name or PSK
`AQ==` (not assumed slot 1). If missing, `offerCreateNavadmin` proposes creating it (channel index 1) via
`AdminMessage.set_channel` (`createNavadminChannel`).

## Other tabs (brief)
- Admin: reboot, wipe NodeDB, set favorite/ignored, get node info.
- Commands: telemetry/position/traceroute/owner-name + free text, decoded-response popups.
- Nodes: card list (favorites first), battery/SNR/last-heard/hops.
- Good Practices: safe read-modify-write of transmit timings.
- Chat: stored + live messages, reply on channel.
- Log: persistent request/response log (last 200 lines).

## Demo mode (screen-recording tour)
The help dialog has a "Usage example (demo)" button (`cmd_demo`) that runs `startDemo()`: a ~65 s scripted
tour with an animated pointer overlay (orange arrow in a circle, `ic_pointer`) added to the decorView. It
simulates a fast connection (~3 s), then walks every tab (Good Practices → Commands → Administration →
NavaTastic CLI → Chat → Nodes → Log), moving the pointer and tapping real controls, showing real popups and
injecting fake decoded responses (telemetry/position), fake chat messages and fake nodes.
Key hooks, all gated on the `demoMode` flag:
- `sendToRadio()` returns fake bytes → all send flows succeed and log.
- `isReady()` (replaces the 8 raw `isConnected()` send-gates) is true in demo mode.
- `connectButton`/`connectBluetoothButton` → `demoSimulateConnect()`; `bpApplyButton` → `demoGoodPracticesApply()`.
- `showTabExplainer` forces popups in demo (doesn't consume the 8x counters); dialogs set `demoActiveDialog`
  so the pointer can tap their positive button (`demoCloseDialog`).
- `stopDemo()` cancels the timeline and hides the overlay; `demoEnd()` resets status/nodes.
To record a promo: open the app, open the "?" help, tap "Usage example (demo)", and record the screen.

## Conventions
- No comments unless they explain non-obvious intent; user-facing strings always via resources.
- Any new destructive NavaTastic command MUST set `warn` and go through `confirmDangerousNava`.
- **F-Droid Packaging Rules (MANDATORY)**:
  1. In `metadata/com.meshkachoutility.yml` under `Builds:`, **ALWAYS use the full 40-character git commit SHA hash** (`commit: <full_hash>`). NEVER use a tag name (e.g. `v1.0.7`) or branch.
  2. For New App inclusion MRs, include **only the single latest build block** in the metadata YAML.
  3. For multi-module projects (`app/`), **ALWAYS set `subdir: app` and DO NOT specify `output:`**. `fdroid build` automatically finds the APK in the module's build directory.
  4. NEVER put machine-specific paths (e.g. `org.gradle.java.home=c:/...`) in `gradle.properties`. Linux CI build runners in F-Droid must rely on their own `JAVA_HOME`.

## Quick Deploy Protocol (`/publicar-release` or "despliegue completo")
When the user requests `/publicar`, `/publicar-release`, `despliegue completo`, or `publica la versión`:
1. **Run `backup.ps1`**: Baseline snapshot and brain backup.
2. **Bump version in `app/build.gradle.kts`**: Increment `versionCode` and `versionName`.
3. **Update Documentation & Badges**: Update `README.md` (ES/EN download badges, direct APK links, test counts, feature highlights) and `Manual_app_MeshNavarra.md` to the new version.
4. **Build signed Release APK**: Run `./gradlew testDebugUnitTest assembleRelease` (verify 28+ unit tests).
5. **Sync & Push to GitHub**: Push all code and doc changes to `EA2OY/MeshNavarra-Utility` on `main`, recreate/push the release tag (e.g. `v1.0.8`).
6. **Get 40-char commit SHA**: `git rev-parse HEAD`.
7. **Update F-Droid metadata**: Update `fdroid/metadata.yml` (single build block, 40-char commit SHA, `subdir: app`, NO `output:`) and update GitLab fork (`jcacho/fdroiddata` branch `meshnavarra` via API).
8. **Publish GitHub Release**: Create/update Release on GitHub with release notes, upload `MeshNavarra-Utility-vX.Y.Z.apk`, and verify HTTP download.
9. **Update `cerebro.md`**: Update State Log + refresh Handover block with session prompt.
10. **Run `backup.ps1`**: Final snapshot.

## Backup & brain-safety protocol (MANDATORY — project has NO git fallback for agents)
The brain (`cerebro.md`) has been emptied twice by PowerShell write failures. Rules to never lose state:
1. **Run `powershell -ExecutionPolicy Bypass -File backup.ps1` at session start and after EVERY completed task** — it snapshots sources+config+brain+testplan (no build) and backs up `cerebro.md`.
2. **Update `cerebro.md` continuously** (state log + known errors as they happen) AND **refresh the HANDOVER block after every task**, not only at session end — so a context-filled session can hand off anytime.
3. **After ANY risky file write, verify the file size/content immediately** (WriteAllText failures can silently empty a file while the listing still shows a valid timestamp).
4. Handoff: when the conversation grows long, write the handover (Objective, Decisions, State, Next step) before continuing.
