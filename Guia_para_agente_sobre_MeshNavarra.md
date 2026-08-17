# Guía para agente sobre MeshNavarra Utility (repo de la app)

Único punto de entrada para cualquier agente de IA o humano que retome este repositorio.
**La app MeshNavarra Utility administra nodos Meshtastic; el firmware de los repetidores (NavaTastic) vive en su propio repo** (`C:\NavaTastic Codigo completo`, SOLO LECTURA).

---

## 0. REGLAS OPERATIVAS (normas principales — leer primero)

1. **Comunicación**: directa y técnica, mínimas palabras, sin introducciones ni relleno.
2. **Flujo en dos fases**: ante cualquier tarea, FASE 1 = diagnóstico + plan técnico conciso + método de verificación, SIN editar archivos; esperar confirmación explícita → FASE 2 = ejecución.
3. **Filtrado de ruido**: no devolver salidas crudas de terminal ni diffs completos — solo líneas de error relevantes y fragmentos modificados.
4. **Código mínimo**: solución con menos líneas y **cero dependencias nuevas**; verificar primero si la funcionalidad ya existe en el proyecto.
5. **Handover**: actualizar `Cerebro_MeshKachoUtility\cerebro.md` (state log + errores→soluciones + pendientes) **sobre la marcha**, y refrescar el bloque §4 HANDOVER tras cada tarea. El brain se ha vaciado 2 veces por fallos de PowerShell: **verificar el tamaño del archivo inmediatamente después de cualquier escritura**.
6. **Autorización de proyectos**: solo se escribe en `C:\Users\Jesus\Desktop\MeshKachoUtility`. `C:\NavaTastic Codigo completo`, `C:\Firmware Navarrico 4.3`, `Desktop\firmware` y `Desktop\Meshtastic-Android-snapshot` son **SOLO LECTURA** (referencias). Cualquier cambio fuera requiere orden explícita y puntual del operador.
7. **Backup/rollback**: ejecutar `powershell -ExecutionPolicy Bypass -File backup.ps1` **al inicio de sesión y tras CADA tarea** (baks rodantes 60 + snaps rodantes 30). Rollback: listar `snap-*.zip`/`cerebro.md.bak-*` y restaurar el más cercano al momento indicado.
8. **Preservar el trabajo existente**: en docs/cerebro AÑADIR (errores + soluciones) en vez de reescribir; no destruir contexto útil.
9. **Commits**: locales por hito solo cuando el operador lo pida o por patrón de sesión establecido; revisar siempre `git status`/`git diff` antes.
10. **Cierre de sesión**: dejar handover completo en el brain + copia redactada del prompt de continuación si el operador la pide.

---

## 1. Qué es esto

Aplicación Android (Kotlin) **no oficial** para administrar nodos **Meshtastic** por **USB OTG** (serial framed `0x94 0xC3`) y **Bluetooth LE** (GATT, protobuf raw). Bilingüe EN/ES, tema táctico DayNight.

**Pestaña estrella: NavaTastic CLI** — control remoto de repetidores con el firmware Navarrico/NavaTastic (repo hermano): diagnóstico por el canal Navadmin (PSK pública, read-only) y control por **DM PKI cifrado** con compuerta de seguridad **CONFIRMAR** para comandos destructivos.

Repos públicos: app = `github.com/EA2OY/MeshNavarra-Utility` · firmware = `github.com/EA2OY/NavaTastic`.

## 2. Dónde está todo (mapa del repo)

| Ruta | Qué es |
|---|---|
| `Cerebro_MeshKachoUtility\cerebro.md` | **EL CEREBRO — leer SIEMPRE primero**: overview, state log (decisiones nuevas primero), errores conocidos, handover en §4. |
| `Cerebro_MeshKachoUtility\01_subnotas\` | Notas 01-09: proyecto, claves/config, seguridad, energía, persistencia, build/distribución, NavaTastic/demo, plan de pruebas, auditoría firmware. |
| `app\src\main\java\com\meshkachoutility\` | Código: `MainActivity.kt` (**monolítico ~6700 líneas**: toda la UI + lógica) · `UsbConnectionManager.kt` · `BleConnectionManager.kt` · `StreamApiFramer.kt`/`StreamApiUnframer.kt` · `MeshPacketBuilder.kt` · `RemoteControlReceiver.kt` · `MeshKachoUtilityApp.kt` (crash-to-file). |
| `app\src\main\proto\meshtastic\` | Protos Meshtastic (protobuf-lite). |
| `app\src\main\res\` | `layout\activity_main.xml` (único layout, 8 paneles), `values/`+`values-es/` (bilingüe), `values-night/`, drawables, mipmaps. |
| `app\src\main\assets\` | 3 PDFs: manual de la app + manuales NavaTastic (abiertos vía FileProvider). |
| `app\src\test\` | **24 tests JVM**: StreamApiUnframer (16) + SharedContactUrl (3) + ChannelSlot (5). |
| `GUIA_AGENTE.md` / `AGENTS.md` | Guía de retoma (normas) + contexto técnico de desarrollo. |
| `Manual_app_MeshNavarra.md` | Manual de usuario completo (español) — fuente del PDF embebido. |
| `README.md` | Versión pública (GitHub), bilingüe ES/EN — no es guía de agente. |
| `fdroid\metadata.yml` | Metadata F-Droid (lista para PR a fdroiddata). |
| `docs_pdf\plantilla_app_meshnavarra.tex` | Plantilla LaTeX del PDF del manual (pandoc + xelatex). |
| `testplan\` | Evidencia de sesiones — **NUNCA se publica** (claves/IPs/posiciones). |
| `jdk-17\`, `.gradle\`, `app\build\` | Toolchain y artefactos — no tocar/commitear. |

## 3. Cómo compilar, testear e instalar

```powershell
$env:JAVA_HOME = "c:\Users\Jesus\Desktop\MeshKachoUtility\jdk-17\jdk-17.0.10+7"
.\gradlew.bat assembleDebug --no-daemon --console=plain          # BUILD SUCCESSFUL
.\gradlew.bat testDebugUnitTest --no-daemon --console=plain      # 24 tests
```

- APK: `app\build\outputs\apk\debug\app-debug.apk`.
- adb (NO en PATH): `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"`.
- Dispositivos de test (WiFi ADB): **Galaxy A10** `192.168.3.193:5555` (Android 11, test) · **Mi 10** `192.168.3.141:5555` (nodo A/test) · **Poco F6** `192.168.3.206:5555` (**principal del operador**).
- Instalar + lanzar: `adb -s <ip>:5555 install -r app\build\outputs\apk\debug\app-debug.apk` → `adb -s <ip>:5555 shell am force-stop com.meshkachoutility` → `adb -s <ip>:5555 shell monkey -p com.meshkachoutility -c android.intent.category.LAUNCHER 1`.
- **Remote control** (test scriptado): `adb shell am broadcast -n com.meshkachoutility/.RemoteControlReceiver -a com.meshkachoutility.REMOTE --es cmd <tab|state|send_nava|request|nodes|fav|ign|remove|chat|audit|audit_stop|debug_tab|import_url> [--es arg ".."]`. OJO: `--es arg` con espacios TRUNCA desde PowerShell; broadcasts implícitos bloqueados en background — usar SIEMPRE `-n` explícito.
- Verificación de UI: `adb shell screencap -p /sdcard/x.png` + `pull` (nunca `exec-out >` con PowerShell, corrompe binarios).

## 4. Cómo funciona la app (rápido)

- **8 paneles, 7 tabs visibles**: Utilidades (Buenas Prácticas ETSI + presets, SFN Spain 869.618 MHz) · Commands · Administration (favoritos/ignorados/eliminar nodo/claves admin PKI/Maestro) · NavaTastic CLI · Chat (por canal, delivery indicator ⟳/✓/✗ + reenviar) · Nodes (caché propia + import por URL + popup 11 acciones) · Log. **El 8º (Debug) está OCULTO por defecto** (ver §5).
- **NavaTastic CLI**: catálogo `NavaCmd` inline en `navaCategories` (48 comandos, 8 categorías); rutas Navadmin (solo lectura) vs DM PKI (control); consulta en blanco/"?"; fragmentos 190 chars concatenados en ventana 15 s; avisos entrantes [Sueño]/[Vivo]/[Listo]/[Boot] realzados con emoji/color.
- **BLE**: handshake de 2 fases (nonces 69420/69421); escaneo continuo + vínculo desde la app (sistema pide PIN — el nodo lo muestra en OLED); detección de vínculo doble vía (broadcast + polling 500 ms); pausa de escaneo al pulsar; batching de resultados 400 ms; teardown/refresh GATT vendor-safe.
- **Chat**: historial por canal (100/canal), entrega por implicit ACK del firmware (3 reintentos los hace el firmware).
- **Demo mode**: tour ~63 s con puntero animado para promos; `demoMode` falsifica `sendToRadio()`.

## 5. Seguridad y secretos (CRÍTICO — leer dos veces)

- **La app no almacena secretos** y nunca toca la clave privada del nodo. Nunca escribas claves privadas, tokens ni PINs en archivos del repo ni en el brain (si aparece algo por accidente, redacta y avisa).
- **PIN BLE del firmware V2 (config General) = 654321**. El PIN `123457` es de una versión antigua y está **eliminado de todo el proyecto y del historial público** — no volver a introducirlo.
- **Pestaña Debug oculta por defecto** (LoRa overrides / duty cycle: sobrepasar el duty cycle ETSI EN 300 220 es ilegal en Europa): solo se muestra con **7 taps rápidos en la línea de estado** o comando remoto `debug_tab on`. NO exponer estas capacidades en docs públicos.
- **GitHub público**: `EA2OY/MeshNavarra-Utility`. Antes de cualquier push: grep de secretos (`privateKey|mxCp|ghp_|123457`) y de mojibake (`[ÃÂâ€œ†–]`); **nunca** subir `testplan\`, `Cerebro_MeshKachoUtility\`, `snap-*.zip`, `*.bak-*`, `local.properties`. El historial público se reescribió limpio (sin claves ni PIN) — no reintroducirlos.
- **Canal Navadmin sin cifrar (PSK pública `{0x01}`)**: por diseño, read-only; la seguridad está en los DM PKI. No reportar como bug.
- **Receiver `RemoteControlReceiver` exported**: herramienta de test aceptada por diseño.

## 6. LO QUE NO HACER (para no cagarla)

1. **No tocar código del firmware** (`C:\NavaTastic Codigo completo`, `C:\Firmware Navarrico 4.3`, `Desktop\firmware`) — SOLO LECTURA.
2. **No renombrar el package** (`com.meshkachoutility`, `MeshKachoUtilityApp`, `Theme.MeshKachoUtility`) — huérfano la app instalada en los 3 teléfonos.
3. **No saltarse la compuerta CONFIRMAR** ni eliminar `warn` de comandos destructivos NavaTastic.
4. **No escribir `set_config` parcial**: toda escritura de configuración es **read-modify-write** (un set_config parcial borra la sección en firmware real).
5. **No tocar views fuera de `runOnUiThread`** desde hilos BLE/lectores (`CalledFromWrongThreadException` — bug histórico del chat).
6. **No usar `emptyList()`/`listOf()` en ArrayAdapter** (crash `UnsupportedOperationException` al añadir; usar `arrayListOf()`/`.toMutableList()`).
7. **No confiar solo en el broadcast `ACTION_BOND_STATE_CHANGED`** (Samsung/MIUI lo entregan tarde o nunca): el polling de `bondState` es la vía fiable.
8. **No escribir literales visibles con acentos/emoji sueltos en MainActivity.kt** si el archivo arrastra mojibake histórico (doble-encoding cp1252→UTF-8): usar `strings.xml` (values + values-es) o escapes `\uXXXX`. Verificar con grep `[ÃÂâ€œ†–]`.
9. **No dejar el cerebro vacío/corrupto**: tras cada escritura, comprobar tamaño (~97 KB) — ver norma 0.5.
10. **No subir secretos ni datos personales a GitHub** (auditar antes; `.gitignore` ya excluye lo sensible).
11. **No usar `cancelBond()`** — no es API pública. Para vínculos atascados (BOND_BONDING): reintentar `createBond()` a los ~400 ms.
12. **No cambiar targetSdk a 35 sin tratar edge-to-edge/insets** en todos los paneles (decisión: targetSdk 34, minSdk 26 — no tocar sin orden).
13. **No olvidar el bilingüe**: cualquier string visible nuevo debe existir en `values/` Y `values-es/`.
14. **No regenerar PDFs del manual sin pandoc + xelatex + `docs_pdf\plantilla_app_meshnavarra.tex`** tras cambiar `Manual_app_MeshNavarra.md`.

## 7. Verificación y entregas

- Toda tarea: **build OK + 24 tests OK** (si se pidieron) + instalación en ≥1 teléfono + verificación visual de cambios de UI (densidades distintas: A10 vs F6).
- Evidencia: `testplan\` (local, no se publica).
- Documentación pública en sincronía: README (ES/EN), manual .md + PDF, metadata F-Droid.

## 8. Estado actual (ir SIEMPRE al handover del cerebro)

El estado vivo está en `Cerebro_MeshKachoUtility\cerebro.md` §4 HANDOVER (objetivo, decisiones, estado, siguiente paso) + §3 state log. Este archivo solo enlaza: **lee el handover antes de tocar nada**.
