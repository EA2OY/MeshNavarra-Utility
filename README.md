# MeshNavarra Utility

[![Licencia: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android: 8.0+](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com/studio)
[![Lenguaje: Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org)

Aplicación Android **no oficial** para administrar nodos **Meshtastic** — por USB OTG y Bluetooth LE — con una pestaña estrella de **control remoto NavaTastic CLI** para repetidores con el firmware Navarrico/NavaTastic (repo hermano: [EA2OY/NavaTastic](https://github.com/EA2OY/NavaTastic)).

---

## Características

| Pestaña | Qué hace |
|---|---|
| **Utilidades** | Buenas Prácticas (read-modify-write consciente del duty cycle ETSI EN 300 220) + presets de radio en un toque (SFN Spain 869.618 MHz + presets de módem estándar) |
| **Commands** | Telemetría, posición, traceroute, cambiar owner + popups con respuesta decodificada |
| **Administration** | Info de nodo, reboot, limpiar NodeDB (conservando favoritos), favorito/bloqueado, eliminar nodo, claves admin (PKI), Convertir en Nodo Maestro |
| **NavaTastic CLI** | Control remoto de repetidores Navarrico/NavaTastic: diagnóstico (canal Navadmin, solo lectura) + control por DM cifrado PKI, con compuerta de seguridad CONFIRMAR para comandos destructivos |
| **Chat** | Historial persistente por canal, indicador de entrega (⟳ en camino / ✓ entregado / ✗ error + reenviar) |
| **Nodes** | Tarjetas de nodo (favoritos primero), caché propia persistente (sobrevive al NodeDB de 80 entradas del nodo), búsqueda inteligente, importar nodos por URL, popup de nodo con 11 acciones |
| **Log** | Consola persistente de peticiones/respuestas |
| **Debug** | Modo de bajo impacto, overrides LoRa (saltos/frecuencia/duty cycle), toggles de sensores, baterías de auditoría automatizadas + control remoto por broadcast ADB |

**También incluye**: interfaz bilingüe EN/ES, tema táctico DayNight, ayuda con pulsación larga en todos los botones, manuales en PDF dentro de la app, modo demo con puntero animado para promos, registro de errores a archivo, control remoto (receiver `com.meshkachoutility.REMOTE`) para pruebas scriptadas.

## Requisitos

- Android **8.0+** (API 26) — probada en Android 12 (MIUI) y Android 15 (HyperOS).
- Un nodo Meshtastic con:
  - **USB OTG** (familia nRF52) o **Bluetooth LE** (emparejarlo antes en los ajustes del sistema; los nodos Navarrico usan PIN `654321`).
- Para la pestaña NavaTastic CLI: un repetidor con el **firmware Navarrico/NavaTastic** (o cualquier nodo estándar para diagnóstico).

## Instalación

1. Descarga el último APK desde [Releases](https://github.com/EA2OY/MeshNavarra-Utility/releases).
2. Permite instalar aplicaciones de fuentes desconocidas en tu dispositivo y abre el APK.
3. Conecta tu nodo por USB OTG o Bluetooth, o ejecuta el modo demo (Ayuda → "Ejemplo de uso (demo)") para verla en acción sin hardware.

La metadata de empaquetado F-Droid está en [`fdroid/`](fdroid/metadata.yml) (publicación pendiente).

## Compilar desde el código

Requiere JDK 17 (el proyecto incluye uno en `jdk-17/jdk-17.0.10+7`).

```powershell
$env:JAVA_HOME = "c:\Users\...\jdk-17\jdk-17.0.10+7"
.\gradlew.bat assembleDebug           # APK debug → app\build\outputs\apk\debug\
.\gradlew.bat testDebugUnitTest       # 24 tests unitarios
```

En Linux/macOS: `JAVA_HOME=/ruta/al/jdk17 ./gradlew assembleDebug`.

Artefactos: `app/build/outputs/apk/debug/app-debug.apk`.

## Seguridad

- La app **no almacena secretos** y nunca toca la clave privada del nodo. Las claves PKI de admin que muestra la pestaña Administration se leen del nodo (solo lectura).
- Los comandos NavaTastic destructivos (`set_chem`, `set_vbat`, `set_vwake`, `storm*`, `db_purge`, `db_clear`, `factory_reset`) exigen escribir **CONFIRMAR**.
- Los comandos de control a repetidores NavaTastic se envían como **DM cifrado PKI**; solo los admins autorizados reciben respuesta.
- Las escrituras de configuración son siempre **read-modify-write** (un `set_config` parcial borra la sección en el firmware real).
- Los cambios de radio requieren reiniciar el nodo para aplicarse.

Ver [SECURITY.md](SECURITY.md) para la política de reportes.

## Documentación

- [`Manual_app_MeshNavarra.md`](Manual_app_MeshNavarra.md) — manual completo de la app (español).
- Dentro de la app: Ayuda → manuales PDF (manual de la app, comandos NavaTastic, uso NavaTastic 4.2).
- La referencia de comandos `/nava` vive en el [repo del firmware](https://github.com/EA2OY/NavaTastic).

## Descargo de responsabilidad (Disclaimer)

Esta es una aplicación **no oficial**, sin afiliación con el proyecto Meshtastic ni con el proyecto Navarrico. Se proporciona "tal cual", **sin garantía de ningún tipo**, expresa o implícita — consulte la [LICENSE](LICENSE) (GPL-3.0) completa. No nos hacemos responsables de daños directos, indirectos o consecuentes derivados de su uso. Una mala configuración del nodo (química de batería, umbrales de sueño, reset de fábrica) puede dejarlo inutilizado hasta recuperación física; úsala bajo tu propia responsabilidad.

## Licencia y contacto

Licenciada bajo la **GNU General Public License v3.0** — ver [LICENSE](LICENSE). Avisos de terceros en [NOTICE](NOTICE).

Contacto: taisoluciones@gmail.com

---

# MeshNavarra Utility (English)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Android: 8.0+](https://img.shields.io/badge/Android-8.0%2B-green.svg)](https://developer.android.com/studio)
[![Language: Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)](https://kotlinlang.org)

Unofficial Android app to administer **Meshtastic** nodes — USB serial and Bluetooth LE — with a flagship **NavaTastic CLI** remote-control tab for repeaters running the Navarrico/NavaTastic firmware (see the companion repo [EA2OY/NavaTastic](https://github.com/EA2OY/NavaTastic)).

## Features

| Tab | What it does |
|---|---|
| **Utilidades** | Good Practices (ETSI EN 300 220 duty-cycle aware read-modify-write) + one-tap radio presets (SFN Spain 869.618 MHz + stock modem presets) |
| **Commands** | Telemetry, position, traceroute, set owner + decoded response popups |
| **Administration** | Get node info, reboot, wipe NodeDB (keep favorites), set favorite/ignored, remove node, admin keys (PKI), Convert to Master Node |
| **NavaTastic CLI** | Remote `/nava` control of Navarrico/NavaTastic repeaters: diagnostics (Navadmin channel, read-only) + control over encrypted PKI DM, with CONFIRMAR safety gate for destructive commands |
| **Chat** | Persistent per-channel history, delivery indicator (⟳ enroute / ✓ delivered / ✗ error + resend) |
| **Nodes** | Visual node cards (favorites first), own persistent cache (survives the node's 80-entry NodeDB), smart search, import nodes by shared URL, rich node popup with 11 request actions |
| **Log** | Persistent request/response console |
| **Debug** | Low-impact mode, LoRa overrides (hops/frequency/duty cycle), sensor toggles, automated audit batteries + remote control via ADB broadcast |

**Also includes**: bilingual UI (EN/ES), DayNight tactical HUD, long-press help on every button, in-app manuals (PDF), demo mode with animated pointer for promos, crash-to-file logging, remote control (`com.meshkachoutility.REMOTE` receiver) for scripted testing.

## Requirements

- Android **8.0+** (API 26) — tested on Android 12 (MIUI) and Android 15 (HyperOS).
- A Meshtastic node with:
  - **USB OTG** (nRF52 family) or **Bluetooth LE** (pair it in system settings first; Navarrico nodes use PIN `654321`).
- For the NavaTastic CLI tab: a repeater running the **Navarrico/NavaTastic firmware** (or any stock node for diagnostics only).

## Installation

1. Download the latest APK from the [Releases](https://github.com/EA2OY/MeshNavarra-Utility/releases) page.
2. Allow installing apps from unknown sources on your device, then open the APK.
3. Connect your node via USB OTG or Bluetooth, or run the app in demo mode (Help → "Usage example (demo)") to see it in action without hardware.

F-Droid packaging metadata is included under [`fdroid/`](fdroid/metadata.yml) (publication pending).

## Build from source

Requires a JDK 17 (the project bundles one at `jdk-17/jdk-17.0.10+7`).

```powershell
$env:JAVA_HOME = "c:\Users\...\jdk-17\jdk-17.0.10+7"
.\gradlew.bat assembleDebug           # debug APK → app\build\outputs\apk\debug\
.\gradlew.bat testDebugUnitTest       # 24 unit tests
```

On Linux/macOS: `JAVA_HOME=/path/to/jdk17 ./gradlew assembleDebug`.

APK artifacts: `app/build/outputs/apk/debug/app-debug.apk`.

## Security

- The app **stores no secrets** on-device and never touches the node's private key. PKI admin keys shown in the Administration tab are read from the node.
- Destructive NavaTastic commands (`set_chem`, `set_vbat`, `set_vwake`, `storm*`, `db_purge`, `db_clear`, `factory_reset`) are gated behind typing **CONFIRMAR**.
- Control commands to NavaTastic repeaters are sent as **PKI-encrypted DMs**; only authorized admins get a response.
- Config writes are always **read-modify-write** (a partial `set_config` wipes the section on real firmware).
- Radio changes need a node reboot to take effect.

See [SECURITY.md](SECURITY.md) for reporting guidelines.

## Documentation

- [`Manual_app_MeshNavarra.md`](Manual_app_MeshNavarra.md) — full app manual (Spanish).
- In-app: Help → PDF manuals (app manual, NavaTastic commands, NavaTastic usage 4.2).
- NavaTastic `/nava` command reference lives in the [firmware repo](https://github.com/EA2OY/NavaTastic).

## Disclaimer

This is an **unofficial** app, not affiliated with Meshtastic or the Navarrico project. Provided "as is", without warranty of any kind — see the full GPL-3.0 [LICENSE](LICENSE). Misconfiguring a node (battery chemistry, sleep thresholds, factory reset) can leave it unusable until physical recovery; use at your own risk.

## License & contact

Licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE). Third-party notices in [NOTICE](NOTICE).

Contact: taisoluciones@gmail.com
