# BajaYa

Descargador de audio y video para Android. Kotlin + Jetpack Compose + MVVM.
Sin anuncios, sin rastreadores, sin analítica. `minSdk 26`, `targetSdk 35`.

> **El nombre "BajaYa" es provisional.** Para cambiarlo: edita `app_name` en
> `app/src/main/res/values/strings.xml` y `rootProject.name` en `settings.gradle.kts`.
> Busca también el texto "BajaYa" en `HomeScreen.kt`, `SettingsScreen.kt` y `AppContainer.kt` (User-Agent).
> Si quieres otro `applicationId`, cámbialo en `app/build.gradle.kts`.

## Cómo abrirlo

1. Android Studio **Ladybug (2024.2)** o más nuevo, con JDK 17 (viene incluido).
2. *File ▸ Open* y elige la carpeta `BajaYa`. La primera sincronización descarga Gradle 8.9 y las librerías.
3. Ejecuta el módulo `app` en un emulador o en tu teléfono (Android 8.0+).
4. Pruebas unitarias (no necesitan emulador): `./gradlew test`.

## Qué incluye (requisito → dónde está)

| Requisito | Dónde |
|---|---|
| 1. Pantalla principal, campo URL, botón "Descargar", detección de portapapeles | `ui/home/HomeScreen.kt`, `HomeViewModel.kt`, `util/Clipboard.kt` |
| 2. Gestor: progreso, pausa, reanudar, cancelar, segundo plano, notificación | `net/FileDownloader.kt`, `work/DownloadWorker.kt`, `work/DownloadNotifications.kt`, `data/DownloadRepository.kt` |
| 3. Guardado en galería con MediaStore, sin permisos de almacenamiento | `data/MediaStoreSaver.kt` |
| 4. Lista con historial (Room) y borrado | `data/db/*`, `ui/downloads/*` |
| 5. Reproductor integrado (Media3 / ExoPlayer) | `ui/player/PlayerScreen.kt` |
| 6. Ajustes: tema claro/oscuro/sistema y carpeta de descarga | `ui/settings/*`, `data/SettingsRepository.kt` (DataStore) |

Extras: recibir enlaces desde "Compartir" de otras apps, selección de varios archivos cuando un
enlace contiene varios, y "Abrir en otra app" cuando un enlace no se puede descargar.

## Arquitectura (MVVM)

```
UI (Compose)  ──►  ViewModel  ──►  Repositorio  ──►  Room · DataStore · MediaStore · WorkManager
 HomeScreen        HomeViewModel     DownloadRepository
 DownloadsScreen   DownloadsVM       SettingsRepository
 SettingsScreen    SettingsVM
 PlayerScreen
```

- Las pantallas solo dibujan el estado (`StateFlow`) y envían eventos al ViewModel.
- `AppContainer` crea y comparte las dependencias a mano (sin Hilt, para mantenerlo simple).
- **Cómo funciona una descarga:** el repositorio guarda la fila en Room y encola un `DownloadWorker`.
  El worker se promueve a **servicio en primer plano** (`setForeground`, tipo `dataSync`) con una
  notificación que tiene botones *Pausar* y *Cancelar*. Descarga a un archivo `.part` en el almacenamiento
  interno y, al terminar, lo copia a la galería con MediaStore.
- **Pausar/reanudar:** pausar cancela el worker y conserva el `.part`. Al reanudar se pide solo lo que falta
  (`Range: bytes=N-`, validado con `If-Range`/ETag). Si el servidor no soporta rangos o el archivo cambió,
  empieza de cero automáticamente.
- **Reintentos:** ante fallos pasajeros (corte de red, 5xx, 429) el worker reintenta hasta 3 veces.

## Conectores (fuentes de descarga)

Cada fuente es una clase que implementa `MediaExtractor` (`extract/MediaExtractor.kt`). El
`ExtractorRegistry` prueba los conectores en orden, de lo más específico a lo más genérico:

1. **`InternetArchiveExtractor`** – usa la API pública de metadatos de archive.org (`/metadata/<id>`) y arma
   los enlaces oficiales `/download/...` de los archivos de audio y video del elemento.
2. **`DirectLinkExtractor`** – enlaces que terminan en `.mp4`, `.mp3`, `.webm`, `.mkv`, `.m4a`, `.ogg`, `.flac`…
3. **`HtmlPageExtractor`** – lee el HTML con **jsoup** y busca `<video>`, `<audio>`, `<source>`,
   metadatos `og:video`/`og:audio` y enlaces a archivos multimedia.

**Añadir un conector nuevo:**

1. Crea una clase que implemente `MediaExtractor` (`canHandle` decide si el enlace es suyo; `extract` devuelve
   una lista de `MediaCandidate` con enlaces directos).
2. Regístrala en `AppContainer.extractors`, antes de `HtmlPageExtractor`.

Sirve para cualquier sitio que ofrezca una API o una descarga pública y documentada.

## Lo que NO hace (a propósito)

La petición original mencionaba imitar a los clientes oficiales de una plataforma para pedir datos del video,
ejecutar fragmentos del reproductor web con Rhino para descifrar enlaces protegidos y delegar en servicios
externos. **No se incluyó lo primero ni lo segundo**: son técnicas para saltarse las protecciones de
plataformas que no ofrecen descarga, y la app se identifica con su propio `User-Agent` (`BajaYa/1.0`).
Por eso `HtmlPageExtractor` solo lee el marcado público y las páginas que entregan el contenido únicamente
como transmisión protegida no darán resultados. Lo tercero sí está: cuando un enlace no se puede resolver, la
app ofrece **"Abrir en otra app"** para que lo maneje el navegador u otra aplicación.

## Permisos

Solo estos, y ninguno de almacenamiento:

- `INTERNET`
- `POST_NOTIFICATIONS` (se pide en Android 13+; si la niegas, las descargas funcionan igual pero sin aviso)
- `FOREGROUND_SERVICE` y `FOREGROUND_SERVICE_DATA_SYNC` (servicio en primer plano de las descargas)

`allowBackup="false"`, tráfico en claro desactivado (solo `https://`; el cliente HTTP rechaza `http://`
incluso en redirecciones).

## Límites conocidos

- **Android 10+:** los archivos van a `Movies/<carpeta>`, `Music/<carpeta>` o `Download/<carpeta>` según el tipo.
  Android no deja elegir una ruta arbitraria sin permisos de almacenamiento, por eso el ajuste "carpeta" es el
  nombre de una subcarpeta dentro de esas tres.
- **Android 8 y 9:** MediaStore no puede escribir sin permisos de almacenamiento, así que se guarda en la carpeta
  propia de la app (`Android/data/<paquete>/files/...`) y el archivo se reproduce desde la app, pero la galería
  del sistema puede no mostrarlo.
- Las descargas se detienen si el sistema mata la app por ahorro de batería agresivo (algunos fabricantes);
  quedan en **Pausa** y se reanudan con un toque.
- Para publicar, firma el APK/AAB con tu propia llave (`Build ▸ Generate Signed Bundle / APK`).

## Responsabilidad

Descarga solo contenido que tengas derecho a guardar (dominio público, licencias abiertas, tus propios archivos,
o lo que el sitio permita descargar expresamente).
