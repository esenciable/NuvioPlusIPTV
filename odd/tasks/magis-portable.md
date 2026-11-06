# Magis portable — sacar el cliente nativo de Magis del mundo Android

Extraer los 15 archivos de `app/src/main/java/com/nuvio/tv/ext/livetv/magis/` a una forma que
compile tal cual en `composeApp` del cliente KMP de teléfono, sin cambiar una sola pantalla del
cliente de TV. Preparación del WU2 del plan (`docs/06-plan-multiplataforma.md` en `esenciable/nuvio`).

## Specs

- **S1** — El cliente de TV (`nuvio-plus-sports`) se comporta **exactamente igual** que antes: misma
  resolución de VOD y de canales Magis, mismas claves `magis:<code>`, mismo `MARKER_SCHEME`.
- **S2** — Ningún archivo del paquete `ext/livetv/magis/` (salvo el glue documentado) importa nada de
  `android.*`, `androidx.*`, `okhttp3.*`, `org.json.*`, `java.*`, `javax.*`, ni modelos de la app de
  TV. Solo `kotlin.*`, `kotlinx.*` y el propio paquete.
- **S3** — Las cuatro dependencias de plataforma se resuelven por **ports** declarados en el paquete:
  HTTP, almacenamiento de sesión/config, cripto (3DES + MD5 + azar) y lookup de título en TMDB. Cada
  app los implementa a su manera; el algoritmo no cambia.
- **S4** — JSON con **`kotlinx.serialization`**, que ya es dependencia del cliente de TV
  (`app/build.gradle.kts:642`), en lugar de `org.json`.
- **S5** — El glue que sí depende de la app de TV queda **explícito y acotado**: hoy solo
  `MagisLiveSource.kt` (produce `LiveChannel`) y `di/LiveTvMagisModule.kt` (Hilt, fuera del paquete).

## Tasks

| ID | Qué | Criterio |
| --- | --- | --- |
| T1 ✅ | Test de frontera `MagisPortabilityTest` (whitelist de imports + glue declarado) | ROJO medido (43 violaciones) y **verde** al cerrar T2-T5 |
| T2 ✅ | Cripto y azar detrás de un port (`MagisCipher`, `MagisDigest`) + implementación Android | `MagisCrypto` y `MagisDevice` sin `javax.crypto`/`java.security`; sus tests actuales verdes |
| T3 ✅ | Almacenamiento de sesión y config detrás de un port (`MagisKeyValueStore`) | `MagisSession` y `MagisRemoteConfig` sin `androidx.datastore`/`android.*`; sus tests verdes |
| T4 ✅ | HTTP detrás de un port (`MagisHttp`) y lookup de título (`MagisTitleLookup`) | `MagisPortal` sin `okhttp3`; `MagisVodTitleLookup` sin `com.nuvio.tv.core.tmdb` |
| T5 ✅ | JSON a `kotlinx.serialization` en los 8 archivos que usan `org.json` | Cero `org.json`; mismos valores decodificados (tests actuales) |

## Log

- **L1** — (2026-11-07) Dueño: «avanza» tras pedir la explicación de WU1.
- **L2** — (2026-11-07) Corrección de método del dueño: «no quiero publicar cosas que ni tu sabes si
  funcionan y no sé qué son por que no investigamos primero». Consecuencia: WU0 (tres fixes de
  deportes) quedó retirado; nada se toca sin síntoma medido. Este documento arranca con un test que
  mide la frontera antes de mover código.
- **L3** — (2026-11-07) Inventario medido: solo 4 archivos tocan plataforma
  (`MagisPortal` okhttp, `MagisRemoteConfig`/`MagisSession` DataStore+android, `MagisCrypto`/
  `MagisDevice` javax.crypto+java.security); `org.json` aparece en 8 archivos con 81 llamadas
  (24 `optString`, 20 `optJSONArray`, 7 `optJSONObject`, 6 `length`, 5 `toString`, 1 `put`,
  1 `optInt`, 39 constructores). El cliente de TV **ya** tiene `kotlinx.serialization.json`.

- **L4** — (2026-11-07) T1-T5 cerrados. Frontera **verde: 43 → 0 violaciones**. Suite del paquete
  `ext.livetv.*`: 138 tests, 0 fallos (incluye los 2 del test de frontera). Suite completa del
  cliente: **2.487 tests, 3 fallos** — exactamente el baseline conocido
  (`TraktAuthServiceTest`, `TraktProgressServiceRefreshTest`, `SettingsCatalogTest`), o sea cero
  regresiones. El paquete quedó con: `MagisCryptoPort` (implementado por
  `platform/AndroidMagisCryptoPort`), `MagisJsonObject`/`MagisJsonArray` sobre kotlinx (con la
  semántica laxa de `org.json` centralizada), `MagisPortalLike`, `MagisSessionStore`,
  `MagisConfigStore` y `MagisTitleLookup`. En `ext/livetv/platform/` viven las cuatro
  implementaciones Android: portal OkHttp, los dos stores DataStore, el fetcher del config remoto
  y el lookup de TMDB.
