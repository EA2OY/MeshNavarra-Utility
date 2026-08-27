---
title: "Manual de Usuario"
subtitle: "Administración de nodos Meshtastic y NavaTastic desde Android"
author: "Tai Soluciones · taisoluciones@gmail.com"
date: "Agosto 2026 · v1.1.1"
colorlinks: true
toc: true
toc-title: "Índice"
---

# Manual de Usuario — MeshNavarra Utility (v1.1.1)

Herramienta Android no oficial para administrar nodos **Meshtastic** (y repetidores **NavaTastic/Navarrico**) por **USB OTG** y **Bluetooth LE**.

**Autor**: Tai Soluciones · **Contacto**: taisoluciones@gmail.com · **Licencia**: GPL-3.0 · **Versión**: 1.1.1 (build 2026-08-27)

> **Aviso importante**: la app se distribuye **TAL CUAL**, sin garantía de ningún tipo. Los comandos de administración (reinicio, borrado de NodeDB, cambios de configuración) pueden afectar al funcionamiento de los nodos. El autor no asume ninguna responsabilidad por daños o mal funcionamiento. Úsala bajo tu propia responsabilidad. Software libre bajo **GNU GPL v3.0**; el código fuente está disponible en GitHub (ver Contacto).

---

## 📋 1. Introducción

MeshNavarra Utility es una app Android que administra nodos de la red Meshtastic directamente desde el teléfono, **sin ordenador**. Ofrece:

- Conexión **USB OTG** (framed serial) y **Bluetooth LE** (GATT, descarga rápida de la NodeDB).
- **8 pestañas** en la barra inferior: Utilidades, Comandos, Administración, NavaTastic CLI, Chat, Nodos y Log (la pestaña **Debug** es una herramienta de desarrollo **oculta por defecto**).
- Administración local y remota de nodos (reinicio, NodeDB, favoritos, bloqueos, configuración).
- **NavaTastic CLI** (la función estrella): control remoto integral de repetidores con el firmware Navarrico/NavaTastic (`/nava`), con soporte ampliado para Frente F21/F22.
- **Nodo destino global**: el nodo elegido en cualquier pestaña se comparte con el resto hasta que se cambie o se borre (X).
- Interfaz **bilingüe EN/ES**, ayuda por pulsación larga en todos los controles, manual integrado, **dos demos guiadas** y licencia GPL-3.0.

> ⚠️ **No afiliada ni respaldada por el proyecto Meshtastic.** Es un fork/herramienta no oficial.

---

## 📋 1b. Novedades de la versión 1.0.5 (Frente F21/F22)

- **Soporte integral NavaTastic F21/F22**: 21 nuevos comandos en NavaTastic CLI distribuidos en categorías temáticas especializadas.
- **Canales secundarios y Redirección CLI**: gestión remota de slots 2-7 (`ch_ls`, `ch_set`, `ch_del`, `ch_url`, `ch_reset`), redirección de consola (`set_cli_chan`) y silenciamiento de rescate (`navadmin_mute`).
- **Asistente generador de claves PSK**: generación aleatoria con un toque de claves seguras Base64 (AES-128 / AES-256 / Default).
- **Control granular de infraestructura y difusión**: pasarelas MQTT por canal (`ch_mqtt`, `set_ok_to_mqtt`), posición fija (`set_pos`, `pos_clear`) y cadencias de transmisión (`set_pos_tx`, `set_nodeinfo_tx`, `set_telem_tx`, `set_beacon`, `set_pin`).
- **Diagnósticos 100% en RAM**: auditoría de extremos térmicos/batería/tráfico (`stats`), buffer circular forense (`log`), modo silencioso de prueba (`mute`) y ráfaga periódica de radio (`test_tx`).

---

## ⚙️ 2. Requisitos

| Elemento | Requisito |
| :--- | :--- |
| **Android** | 8.0+ (minSdk 26) |
| **Nodo Meshtastic** | Cualquiera compatible (USB OTG o BLE) |
| **Nodo NavaTastic** | Repetidor con firmware Navarrico/NavaTastic (`/nava`) |
| **Permisos** | USB OTG · Bluetooth (BLE) · Localización (Android 12 o inferior para el escaneo BLE) |
| **Sin permisos** | Sin INTERNET — la app no recopila ni envía datos |

---

## 🔌 3. Conexión al nodo

### 3.1 Por USB OTG (cable)

1. Enchufa el nodo al teléfono con un cable OTG.
2. Pulsa **«Conectar por USB»** (o conéctate solo: la app lo detecta automáticamente al arrancar o al conectar el cable).
3. Acepta el diálogo de permiso USB (se pide en cada relanzamiento por diseño de Android).
4. La NodeDB se descarga automáticamente: verás «Conectado — listo (N nodos en NodeDB)».

### 3.2 Por Bluetooth LE

1. Pulsa **«Conectar por Bluetooth»**.
2. Elige el nodo **ya vinculado** en el selector (ej. `NetT_aca9`, `Meshtastic_ac94`).
3. La app realiza un handshake de configuración en 2 fases (config+canales → NodeDB) y descarga los nodos.

> 🔑 **Nodos NavaTastic**: el PIN BLE es fijo (**654321**) y se muestra en la pantalla OLED del nodo en cada vinculación. El emparejamiento se hace manualmente con la app oficial; MeshNavarra solo se conecta a dispositivos ya vinculados.

### 3.3 Auto-reconexión

Si el enlace se cae, la app reconecta automáticamente (5 intentos × 5 s). Al reiniciar un nodo por USB, el bus se re-enumera y aparece un nuevo diálogo de permiso — es normal, acéptalo.

---

## 🗂️ 4. Pestañas (barra inferior)

| # | Pestaña | Función |
| :--- | :--- | :--- |
| 0 | **Utilidades** | Buenas Prácticas SFN + presets de radio de un toque |
| 1 | **Comandos** | Telemetría, posición, traceroute, owner y texto libre |
| 2 | **Administración** | Reinicio, NodeDB, favoritos, bloqueos, claves admin |
| 3 | **NavaTastic CLI** | Control remoto `/nava` de repetidores NavaTastic |
| 4 | **Chat** | Mensajería por canal con indicador de entrega |
| 5 | **Nodos** | Tarjetas visuales de nodos (favoritos primero) |
| 6 | **Log** | Registro persistente de peticiones/respuestas |
| 7 | **Debug** | Herramientas de desarrollo (oculta por defecto): bajo impacto, sensores y auditorías |

Barra desplazable con flechas de borde; también se navega deslizando. Cada pestaña muestra un popup explicativo las primeras veces.

> **Cabecera scrolleable**: el bloque de estado/conexión vive dentro del contenido de la pestaña activa — al hacer scroll sube con el resto y deja más espacio. Vuelve a aparecer al scrollear arriba.

> **Nodo destino global**: el campo de destino (Administración, Comandos, Utilidades y NavaTastic) es el **mismo para toda la app**. Lo que escribas o elijas en una pestaña aparece en las demás; la **X** del campo lo deselecciona en todas.

---

## 🛠️ 5. Pestaña Utilidades

### 5.1 Buenas Prácticas (SFN)

Aplica de forma segura los tiempos de envío recomendados (protocolo ETSI 868 MHz, ciclo de trabajo) mediante **read-modify-write** (nunca pisa el resto de la configuración):

- **Límite de saltos**: 5.
- **NodeInfo / baliza GPS**: cada 72 h.
- **Smart position**: desactivado · **GPS**: desactivado.

Con el selector de **destino** puedes aplicarlo al nodo conectado o a un nodo remoto (tu clave pública debe estar en su lista de admin; el remoto aplica 3 comandos en secuencia: saltos, NodeInfo y posición).

Incluye **copia de seguridad / restauración** de la configuración (JSON) y su botón de ayuda.

### 5.2 Presets de radio (un toque)

Spinner con dos grupos:

- **SFN España**: 869.618 MHz / ShortFast Narrow (62 kHz, SF7, CR5, slot 4).
- **Presets stock de Meshtastic**: LONG_FAST, MEDIUM_FAST, SHORT_FAST, SHORT_TURBO, LONG_TURBO, SHORT_SLOW, MEDIUM_SLOW, LONG_SLOW, LONG_MODERATE (con el canal 0 reseteado a su nombre/PSK por defecto y frecuencia derivada del hash del canal).

> ⚠️ **Los cambios de LoRa requieren reinicio** para re-sintonizar la radio.

---

## 🎛️ 6. Pestaña Comandos

Pide acciones al nodo elegido (vacío = nodo conectado/difusión) y muestra la respuesta decodificada en un popup:

| Acción | Descripción |
| :--- | :--- |
| **Telemetría** | Batería, voltaje, uso de canal/aire |
| **Posición** | Latitud/longitud/altitud |
| **Traceroute** | Ruta al nodo (saltos, SNR) |
| **Cambiar nombre (owner)** | Cambia el nombre del nodo |
| **Texto libre** | Envía un mensaje de texto al canal |

---

## ⚙️ 7. Pestaña Administración

Dirigida al nodo indicado (ID o selector con búsqueda; vacío = local):

- **Consultar Nodo** — vuelve a descargar la NodeDB.
- **Reiniciar Nodo** — reboot diferido.
- **Restablecer NodeDB** — con casilla **«Mantener Favoritos»**.
- **Reset de fábrica**.
- **Marcar / Quitar Favorito**.
- **Marcar / Quitar Ignorado**.
- **Claves de administración** — muestra la clave pública/privada y las claves admin del nodo; permite añadir claves y **«Convertir en Nodo Maestro»** (escribe la clave privada de rescate y reinicia).

---

## 🚀 8. Pestaña NavaTastic CLI (la estrella)

Control remoto de repetidores con el **firmware Navarrico/NavaTastic** (fork de Meshtastic optimizado para repetidores solares de infraestructura). El módulo `NavaCLIModule` del firmware intercepta los comandos `/nava`. Compatible con **NavaTastic Eclipse V5 (v4.3.7)** y anteriores (V5/V5.2).

### 8.1 Cómo funciona

1. Elige el **nodo destino** (favoritos primero) en el campo de destino.
2. Elige la **ruta**:
   - **Navadmin** (canal): solo comandos de lectura/consulta. Respuestas en lote con jitter; los no-admins no reciben respuesta.
   - **DM privado (PKI)**: comandos de control, cifrados. Requiere que tu clave pública esté acreditada como admin en el repetidor.
3. Elige **categoría** y **comando**; el campo de argumento se adapta (texto, número, ID de nodo u opciones).
4. La línea de **preview** muestra exactamente lo que se enviará (`/nava ...`).
5. Pulsa **enviar**. Los comandos peligrosos exigen escribir **CONFIRMAR** (en `full_reset` y `wipe` la app añade además automáticamente el token `CONFIRM` que exige el nodo).

### 8.2 Niveles de seguridad

| Nivel | Vía | Alcance |
| :--- | :--- | :--- |
| **Canal Navadmin** | Canal abierto (PSK pública) | Solo lectura y diagnóstico |
| **DM PKI** | Mensaje directo cifrado | Configuración, reinicio, DB, bloqueos, favoritos, energía |

Los comandos **de control se muestran en rojo** si la ruta activa es Navadmin (no se pueden enviar por ese canal). Si el canal Navadmin no existe en el nodo, la app ofrece crearlo (slot libre secundario).

> **Navadmin solo lee y solo dirigido**: en el canal público el repetidor solo responde sin `!ID` a los 7 comandos ligeros (`ping`, `status`, `bat`, `power`, `env`, `channel`, `noise`). Los diagnósticos `stats`, `log`, `peers`, `rxlog`, `afc`, `reset_reason`, `help` y `ch_ls` exigen **nodo destino (!ID)** — la app lo valida antes de enviar y te avisa si falta. `route`/`trace` llevan el `!ID` como argumento propio. `panic` y `panic_ok` están **bloqueados en Navadmin** (solo DM PKI o canal privado de flota).

### 8.3 Interrogación y consulta (firmware 4.4+)

- **Botón «?»**: envía `/nava <comando> ?` al nodo y muestra su ayuda/estado en vivo (con fallback a la descripción local si no hay conexión).
- **Consulta en blanco**: enviar un comando sin argumento (o elegir **«— Consultar —»** en los desplegables) devuelve el **estado actual** del nodo en lugar de un error (p. ej. `/nava set_hops` → `HOPS ACT: 3 (1-7)`).
- Las respuestas largas llegan fragmentadas (190 caracteres / 12 s) y la app las **concatena automáticamente** en un solo mensaje.

### 8.4 Catálogo de comandos

**📊 Diagnóstico (Navadmin o DM)**
| Comando | Descripción |
| :--- | :--- |
| `/nava ping` | Latencia, uptime y piso de ruido |
| `/nava status` | Salud de memoria, favoritos, Auto-Fav, tiempo activo |
| `/nava env` | Batería, heap, temperatura y sensor ambiental I2C |
| `/nava channel` | Uso de espectro (airtime % y TX %) |
| `/nava power` | Métricas de energía: ADC interno + sensor I2C (INA219) |
| `/nava peers` | Vecinos directos a 0 saltos |
| `/nava rxlog` | Metadatos de los últimos 5 paquetes |
| `/nava afc` | Deriva de frecuencia del TCXO |
| `/nava reset_reason` | Motivo del último reinicio |
| `/nava noise` | Piso de ruido instantáneo |
| `/nava bat` | Química, voltaje, % OCV y estado TX |
| `/nava stats` | Auditoría en RAM (temps min/max/act, batería mínima, paquetes RX/TX/Rout) |
| `/nava log [1-15]` | Buffer circular de eventos en RAM (1-15 líneas) |
| `/nava help` / `/nava help <cmd>` | Glosario / ayuda de un comando |
| `/nava route !ID` / `/nava trace !ID` | Ruta / traceroute a un nodo |

**📻 Canales y CLI (solo DM)**:
- `ch_ls`: Lista los 8 slots de canales (0-7), roles, marcas CLI, nombres, crypto y MQTT.
- `ch_set <slot 0|2-7> <nombre> <psk_base64>`: Configura canal primario (slot 0) o secundario privado (slots 2-7). Asistente de claves integrado.
- `ch_del <slot 2-7>` ⚠: Deshabilita canal y libera persistencia.
- `ch_url [slot 0-7]`: Genera URL canónica de Meshtastic (`meshtastic.org/e/#...`).
- `ch_reset` ⚠: Restaura la tabla de canales a valores de fábrica.
- `set_cli_chan <slot 1-7>`: Redirige la escucha de NavaCLI y avisos solares al slot indicado.
- `navadmin_mute [on|off]`: Silencia o activa el canal de rescate (Slot 1 Navadmin).

**⚡ Radio y Pánico (Novedad V5)**:
- `set_preset <preset>` ⚠: Aplica preset LoRa estándar (`long_fast`, `medium_fast`, `short_fast`, `long_slow`, `short_slow`, `medium_slow`, `long_moderate`, `short_turbo`) y reinicia módem en 6 s.
- `set_lora <bw> <sf> <cr> <freq> <slot> [txpower]` ⚠: Configuración Custom de capa física LoRa. Reinicia módem en 6 s.
- `set_freq <freq_mhz> [slot]` ⚠: Ajusta atómicamente la frecuencia central y slot en 6 s.
- `panic <preset|sfnarrow> [minutos_aviso=10] [minutos_prueba=0]` ⚠: Inicia evacuación de emergencia en la malla con túnel silencioso en $T-60\text{s}$.
- `panic_ok`: Cancela el auto-rollback y consolida definitivamente la nueva frecuencia/preset. **Solo por DM (PKI)** — bloqueado en el canal público Navadmin (V5.2).

**🌐 Infraestructura y Difusión (solo DM)**:
- `ch_mqtt <slot 0-7> [up|down|both|off]`: Compuerta MQTT individual por canal.
- `set_ok_to_mqtt [on|off]`: Bandera global OK_TO_MQTT en paquetes para pasarelas.
- `set_pos <lat> <lon> [alt]`: Coordenadas geográficas estáticas fijas.
- `pos_clear`: Borra la posición fija guardada.
- `set_pos_tx [on|off|minutos]`: Difusión periódica de posición en flota (default 72h; el OFF persiste).
- `set_nodeinfo_tx [on|off|minutos]`: Difusión periódica de NodeInfo en flota (default 72h; el OFF persiste).
- `set_telem_tx [on|off|minutos]`: Intervalo de reporte de telemetría (default 12h = 720 min; cambia los **5 tipos a la vez**; el OFF persiste; por tipo solo desde la App oficial).
- `set_beacon [1-1440]`: Cadencia de balizas NodeInfo/Posición.
- `set_pin <6_digitos>`: PIN Bluetooth fijo persistente.
- `mute [1-1440|off]`: Silenciado temporal de reenvío LoRa en RAM.
- `test_tx [5-30]`: Ráfaga de prueba RF periódica para alineación.

**🚫 Bloqueos (solo DM)**: `ign ls` · `ign add !ID` ⚠ · `ign rm !ID` · `ign clear` ⚠

**⭐ Favoritos (solo DM)**: `fav ls` · `fav add !ID` · `fav rm !ID` · `fav auto [on|off]`

**⚙️ Configuración (solo DM)**: `set_name` (soporta "Largo" "Corto" y `flush`) · `set_role` · `set_rebroadcast` · `set_mqtt` · `set_tz` · `set_hops` · `set_txpower`

- `set_rebroadcast [all|local|known|core|none]`: cómo retransmite el nodo los paquetes ajenos. Persiste y sobrevive a resets; se sincroniza con la App oficial. `none` es rechazado en rol ROUTER; `all`/`known`/`core` pueden reducir la cobertura NavaCLI multi-salto.
- `set_role` (revisión 28/08): al cambiar el rol **ya no se re-aplican los defaults del rol** — tus intervalos y tu modo de retransmisión se mantienen.

**🧹 Mantenimiento (solo DM)**: `db_purge` ⚠ · `db_clear` ⚠ · `reboot` (gracia 6s) · `factory_reset` ⚠ · `full_reset` ⚠ · `wipe` ⚠ — en `full_reset` y `wipe` la app añade automáticamente el token `CONFIRM` que el nodo exige.

**🔋 Energía (solo DM)**: `set_chem` ⚠ · `set_vbat` ⚠ · `set_vwake` ⚠ · `storm [1-720]` ⚠ · `storm test1/test2` ⚠ · `txoff` ⚠ · `txon` · `ble [on/off]` ⚠ · `sleepmsg [on/off]`

**📡 Transmisión (solo DM)**: `msg` · `pos` · `nodeinfo` · `sendtel` · `power` · `test_tx`

**🔔 Utilidades (solo DM)**: `bell` · `admin_ls` · `keys_ls` · `keys_clear` ⚠

> ⚠ = exige **CONFIRMAR**. Los comandos que persisten configuración (`set_preset`, `set_lora`, `set_freq`, `panic`, `set_chem`, `set_vbat`, `set_vwake`, `txoff`, `ble`, `ch_del`, `ch_reset`, `ign clear`, `keys_clear`) advierten: el rollback solo es posible con `nrf erase` o comandos específicos. `storm` y `mute` dejan una **ventana de gracia de 60 s** antes de actuar — no reenvíes más órdenes durante ese tiempo.

### 8.5 La conversación

La pestaña separa las respuestas en **NAVADMIN** y **DM PRIVADO**, con hora y emisor. El historial se guarda en disco y sobrevive a reinicios de la app. Los comandos con `warn` muestran un aviso rojo y bloquean el envío hasta escribir **CONFIRMAR**.

> **Divisor ajustable**: la barra de puntos entre los controles y la conversación se puede arrastrar arriba/abajo para repartir el espacio entre ambas zonas; la proporción se recuerda entre sesiones.

---

## 💬 9. Pestaña Chat

- Historial **por canal** (SFNarrow, Navadmin, etc.): selecciona el canal en el desplegable y solo verás ese historial.
- **Indicador de entrega**: junto a tus mensajes enviados —
  - 🔵 **enviando** en camino (ENROUTE)
  - 🟢 **✓** entregado a la malla (DELIVERED)
  - 🔴 **✗** error (ERROR)
- Toca un mensaje enviado para ver su **diálogo de estado** (detalle del error, relevos) y **reenviar** si el error es recuperable (no NO_CHANNEL/TOO_LARGE).
- Historial persistente (`chat/history.json`, 100 mensajes por canal).
- **Pausar** para detener el auto-scroll durante tareas de administración.

---

## 👥 10. Pestaña Nodos

Tarjetas visuales con **favoritos primero**: nombre, ID, batería, voltaje, SNR, último contacto y saltos. Incluye:

- **Buscador** con búsqueda por nombre largo/ID/nombre corto.
- **Caché local** del NodeDB (sobrevive a reinicios; los nodos vivos la actualizan).
- **Popup de nodo** (tocar una tarjeta): 11 botones — Info · Telemetría · Ambiente · Energía · Posición · Traceroute · Vecinos · Señal · Aire · Host · **Compartir** (genera la URL de la tarjeta del nodo). Solicitudes enviadas por protocolo estándar o por **NavaCLI (DM PKI)**.
- **Añadir por URL**: pega una URL de tarjeta de nodo (`meshtastic.org/v/#...`) para importar el nodo a la caché (intercambia NodeInfo si falta la clave pública).

---

## 📝 11. Pestaña Log

Registro persistente de cada petición y respuesta con marcas de tiempo (últimas 200 líneas). Los errores de decodificación y los ACK/NAK también se registran.

---

## 🧪 12. Pestaña Debug (herramienta de desarrollo, oculta por defecto)

La pestaña **Debug es una herramienta de desarrollo y pruebas** y está **oculta por defecto** para uso normal. Puede mostrarse puntualmente desde el panel de estado (7 pulsaciones rápidas) cuando se necesiten pruebas en banda de laboratorio.

Incluye:

- **Modo de bajo impacto**: limita todos los paquetes a **1 salto** y fuerza hop 1 en el nodo (ideal para bandas de pruebas). Al **desactivarlo**, restaura la configuración del nodo (primero saltos, luego frecuencia).
- **Sensores**: toggles 🌡 **Ambiente** (BME680: `environment_measurement_enabled` + `air_quality_enabled`) y ⚡ **Energía** (`power_measurement_enabled`), vía read-modify-write.
- **Auditorías automatizadas** (baterías de test en vivo, con popup de consola en color y archivo de resultados):
  1. **Navadmin** (solo lectura, 32 s entre comandos)
  2. **Comandos → B** (telemetría/posición/traceroute)
  3. **Admin local (A)** (set-favorite, reboot)
  4. **Chat** (mensaje de prueba)
  5. **Admin remota PKI (B)**
  6. **DM control (B, seguros)**
  7. **Config get (A, solo lectura)**

> ⚠️ **Uso responsable**: usa estas herramientas solo en banda de pruebas aislada y respetando siempre la normativa ETSI EN 300 220 de la banda de 868 MHz (ciclo de trabajo y potencia).

> Los comandos destructivos (`db_purge`, `db_clear`, `storm`, `set_chem`, `set_vbat`, `set_vwake`, `factory_reset`) quedan **manualmente** detrás de CONFIRMAR — nunca se automatizan.

---

## 🎬 13. Modos demo

Desde Ayuda hay **dos demos guiadas** (no requieren nodo físico y se bloquean si hay una conexión real activa):

- **«Ejemplo de uso (demo)»**: recorrido clásico de ~52 s con puntero animado, conexión simulada, nodos/chat/respuestas ficticias y popups reales.
- **«Tour guiado con globos (demo 2)»**: recorrido completo (~90 s) que explica cada función con **globos de texto** sobreimpresos — graba la pantalla y añade solo una pista de audio, no necesita voz.

Ambas empiezan siempre desde un **estado limpio** (como recién abierta la app) y tienen un botón **«Parar demo» abajo a la derecha**. Los datos simulados (nodos ficticios, chat, consola) se eliminan al terminar o parar; tus nodos reales no se tocan.

---

## ❓ 14. Ayuda y Contacto

- **Pulsación larga** en cualquier control: burbuja de ayuda breve; al soltar, diálogo completo.
- **Botón de ayuda** (arriba derecha): manual integrado, licencia GPL-3.0 (texto completo), NavaTastic PDF, las dos demos, y selector de idioma (EN/ES).
- **Correo**: pulsa `taisoluciones@gmail.com` en Ayuda para abrir tu app de correo con el asunto pre-rellenado «Consulta sobre la app MeshNavarra».
- **Código fuente**: enlace clickable a `https://github.com/EA2OY/MeshNavarra-Utility` (Ayuda y aviso de bienvenida).

---

## 📄 15. Aviso legal

MeshNavarra Utility se proporciona **TAL CUAL**, sin garantía expresa o implícita (incluidas comerciabilidad e idoneidad para un fin). Los comandos de administración pueden afectar al funcionamiento de los nodos. En la máxima medida permitida por la ley, el autor no asume responsabilidad por daños directos, indirectos o consecuentes, y no se admite reclamación alguna contra el autor derivada del uso de este software.

Software libre bajo **GNU GPL v3.0**. Esta aplicación **no está afiliada ni respaldada por el proyecto Meshtastic**.
