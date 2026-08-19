# AGENT INITIALIZATION GUIDE — MeshNavarra Utility

> **ÍNDICE DE RETOMA (leer primero)**: [`Guia_para_agente_sobre_MeshNavarra.md`](Guia_para_agente_sobre_MeshNavarra.md) — guía unificada estilo firmware: qué leer y dónde, normas operativas, mapa del repo, build/test/instalación, seguridad/secretos y la lista de "lo que NO hacer". Este archivo es la versión breve de normas + herramientas.

Single entry point for any (AI) agent resuming work on this project. Read it fully **before touching any file**.

---

## 1. What to read and in what order (MANDATORY)

1. **`Cerebro_MeshKachoUtility\cerebro.md`** — canonical project memory (overview, components, decisions, state log and **handover**). Read it together with its subnotes `Cerebro_MeshKachoUtility\01_subnotas\`.
2. **Project context documents**:
   - `AGENTS.md` (root) — build/ADB, architecture, NavaTastic, demo.
   - `Cerebro_MeshKachoUtility\PROMPT_INICIALIZACION_BLANK.md` and `despliegue_cerebro.md` — operation pautas (token diet, two phases, handover).
   - **Firmware Navarrico 4.3** (the node firmware this app controls): `C:\Firmware Navarrico 4.3\Contexto y Manuales\` → `cerebro\cerebro.md`, `Manual_NavaTastic.md/.pdf`, `guia_integracion_navarrico.md`, `transfer_context.md`. Reference only — **read-only, no edits**.
3. Subnote `07_navatastic_demo.md` for NavaTastic CLI + demo + promo.

> The brain is **linked** to the documentation: when detail is missing in a subnote, check the corresponding context document. The `handover` (§4 of `cerebro.md`) marks current state and next step.

---

## 2. Golden rules

- **🚫 PROJECT AUTHORIZATION (critical)**: DO NOT modify or create content in projects other than this one (e.g. `C:\Firmware Navarrico 4.3\`, `Desktop\firmware\`). Only this project (**MeshKachoUtility / MeshNavarra Utility**) is writable. Any change elsewhere requires an **explicit order from the user limited to that specific item** — never generalized.
- Visible brand is **MeshNavarra Utility**; technical identifiers (`com.meshkachoutility`, `MeshKachoUtilityApp`, `Theme.MeshKachoUtility`) are NOT renamed (would orphan the installed app).
- Every destructive/risky NavaTastic command must carry `warn` and go through `confirmDangerousNava` (type `CONFIRMAR`). Never bypass.
- `set_config` replaces the whole section → always configure via **read-modify-write**.
- `edge-tts` reads SSML tags aloud: never pass `<break>/<prosody>` in the text; use real silence (ffmpeg concat).
- User-facing strings always in `values/` + `values-es/` (bilingual).
- **Operation mode**: minimal token consumption — concise output, two-phase flow (plan → confirmation), noise filtering. See `PROMPT_INICIALIZACION_BLANK.md`.

---

## 3. Project tools

| Tool | Path / command |
|---|---|
| Build | `$env:JAVA_HOME="c:\Users\Jesus\Desktop\MeshKachoUtility\jdk-17\jdk-17.0.10+7"`; `.\gradlew.bat assembleDebug` |
| Tests | `.\gradlew.bat testDebugUnitTest` (16 tests) |
| Install on phone | `adb -s 192.168.3.206:5555 install -r app\build\outputs\apk\debug\app-debug.apk` |
| ADB | not on PATH → `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe` |
| ffmpeg | Python `imageio-ffmpeg` (binary in site-packages) |
| TTS | Python `edge-tts` + `mutagen`; generator `%TEMP%\opencode\tts_final3.py` |
| Firmware Navarrico | `C:\Firmware Navarrico 4.3\` (context in `Contexto y Manuales\`, read-only) |

---

## 4. Workflow

1. Load the brain and the docs (section 1).
2. **PHASE 1 (PLAN)**: diagnose and present a concise technical plan + verification method. Do not edit files.
3. Wait for explicit confirmation before **PHASE 2 (EXECUTION)**.
4. Keep the brain updated continuously: record in `Cerebro_MeshKachoUtility\cerebro.md` (state log, pending tasks, **known errors + solutions**) everything done, noting errors as they happen — not only at the end.
5. When done, or if the conversation grows long: generate the handoff block (Objective, Decisions, State, Next step).

---

## 5. Quick Deploy Protocol (`/publicar-release` / `despliegue completo`)

When the user triggers `/publicar`, `/publicar-release`, `despliegue completo`, or `publica la versión`:
1. `backup.ps1` (snapshot + brain backup).
2. Bump version in `app/build.gradle.kts` (`versionCode` + `versionName`).
3. Update `README.md` (badges, direct download links, test counts) & `Manual_app_MeshNavarra.md`.
4. `./gradlew testDebugUnitTest assembleRelease` (verify all unit tests).
5. Commit and push to GitHub `main` + recreate release tag (e.g. `v1.0.8`).
6. Update `fdroid/metadata.yml` (single build block with 40-character commit SHA + explicit `output: app/build/outputs/apk/release/app-release-unsigned.apk`) and sync to GitLab fork (`jcacho/fdroiddata`).
7. Create/update GitHub Release with official notes and attach `MeshNavarra-Utility-vX.Y.Z.apk` (verify via HTTP download).
8. Update `cerebro.md` (State log + Handover + session prompt).
9. `backup.ps1` final snapshot.
