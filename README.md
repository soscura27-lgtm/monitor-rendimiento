# Monitor de rendimiento

Barra flotante para Android con FPS, ping, CPU, GPU, RAM, temperaturas y batería, con tema
espacial ("Sistema de monitoreo estelar"). Se dibuja encima de cualquier juego o app.

## Qué trae (versión 2.0)

- **Barra flotante en dos vistas**: compacta (una tira con los datos) y **panel estelar**
  (tarjetas de FPS y ping, barras de CPU/GPU/RAM, batería, estado térmico, app que más RAM usa
  y gráfica de FPS). Toca la barra para cambiar de vista; mantenla presionada para abrir la app.
- **FPS en tiempo real** que funcionan en Android 13 a 16 (incluido HyperOS).
- **Ping en tiempo real** al servidor del juego, con promedio, jitter y pérdida de paquetes.
- **Consumo por app**: pestañas de CPU, RAM, almacenamiento, datos de hoy, batería y tiempo de
  uso, con icono y nombre de cada app. Tocar una app muestra todo su consumo y permite
  forzar su detención. Botón **Liberar RAM** (cierra apps en segundo plano).
- **Historial de sesiones**: al cerrar un juego se guarda su FPS promedio/mínimo, ping,
  temperatura máxima y batería gastada.
- **Mosaico "Monitor"** en los ajustes rápidos para encender o apagar la barra sin abrir la app.
- Actualización cada 0,5, 1 o 2 segundos; posición distinta para vertical y horizontal.

## Qué mide y qué necesita

| Dato | Necesita Shizuku |
| --- | --- |
| FPS, ms por fotograma y gráfica de la app al frente | Sí |
| Ping al servidor del juego | Sí (sin Shizuku se mide el ping a internet) |
| CPU (%), GPU, temperaturas de CPU/GPU | Sí (casi siempre) |
| Consumo por app: CPU, RAM exacta, batería, forzar detención, liberar RAM | Sí |
| Consumo por app: almacenamiento, datos de hoy, tiempo de uso | Acceso a datos de uso (la app se lo da sola con Shizuku) |
| RAM total, batería (%, temperatura, mA, V, W), estado térmico, Hz | No |

Android no deja que una app normal vea los FPS de otras apps ni el uso de CPU. Por eso la app
usa [Shizuku](https://shizuku.rikka.app/), que da permisos de shell (como adb) sin root.

### Cómo se calculan los FPS

1. **FPS del task (Android 13+)**: el proceso de shell registra un `ITaskFpsCallback` en
   WindowManager (`registerTaskFpsCallback`, permiso `ACCESS_FPS_COUNTER`, que el usuario shell
   tiene). SurfaceFlinger reporta los FPS de todas las capas del juego cada ~0,5 s. Es lo mismo
   que usa el "Game Dashboard" del sistema.
2. **`dumpsys SurfaceFlinger --latency <capa>`**: da el tiempo de cada fotograma (ms y 1% low).
   En Android 15/16 `--list` escribe `RequestedLayerState{nombre parentId=…}`; ahora se saca el
   nombre correcto de la capa. En algunos teléfonos con Android 15+ ya no devuelve datos, por eso
   la fuente 1 es la principal.

### Cómo se mide el ping

- Se buscan las conexiones del juego en `/proc/net/tcp`, `tcp6`, `udp` y `udp6`. Antes solo se
  leían `tcp` y `udp` (IPv4), pero Android abre casi todos los sockets como IPv6 con la IPv4
  adentro (`::ffff:a.b.c.d`), así que el juego nunca aparecía ("buscando servidor").
- Se prefiere UDP con puerto distinto de 443 (tráfico de la partida), luego TCP.
- Medición: RTT del kernel para conexiones TCP (`ss -ti`), ping ICMP (`ping`/`ping6`) y, si el
  servidor bloquea ICMP, el tiempo del saludo TCP. Si no hay juego, ping a internet (1.1.1.1).

## Cómo usarla

1. Instala la app y abre **Monitor de rendimiento**.
2. Toca **Permitir superposición** y activa el permiso.
3. Instala **Shizuku**, actívalo (depuración inalámbrica → emparejar → Iniciar) y toca
   **Dar permiso** en esta app. Hay que volver a iniciar Shizuku cada vez que se reinicia el teléfono.
4. Toca **Iniciar barra flotante**. Arrastra la barra donde quieras.
5. Para cerrarla usa el botón de la app, **Detener** en la notificación o el mosaico **Monitor**.

En Xiaomi/HyperOS, si Shizuku falla al emparejar, activa también "Depuración USB (ajustes de
seguridad)" en Opciones de desarrollador y pon el estilo de notificaciones en "Android".

## Cómo compilarla

Requiere JDK 17 y el SDK de Android (compileSdk 35).

```
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`. En Bitrise se usa el workflow
`build_apk`.

## Estructura

- `MainActivity.kt`: pantalla principal (panel en vivo, permisos, Shizuku, opciones, historial).
- `ConsumoActivity.kt`: sección "Consumo por app" con pestañas, detalle por app y liberar RAM.
- `OverlayService.kt`: servicio en primer plano que dibuja la barra y la actualiza.
- `OverlayViews.kt`: dibujo de la barra compacta y del panel estelar.
- `OverlayTileService.kt`: mosaico de ajustes rápidos.
- `Sampler.kt`: reúne RAM, batería, CPU, GPU, temperaturas, FPS, ping y consumo.
- `FpsEngine.kt`: FPS por task y por capas de SurfaceFlinger.
- `PingEngine.kt`: ping al servidor del juego (IPv4/IPv6, ICMP, TCP, RTT del kernel).
- `AppMonitor.kt`: CPU y RAM por paquete a partir de la tabla de procesos.
- `AppData.kt`: nombres/iconos de apps, RAM exacta (PSS), almacenamiento, datos, batería y uso.
- `Sessions.kt`: historial de sesiones de juego.
- `ShellClient.kt`: única conexión con Shizuku para toda la app.
- `ShellService.kt` + `IShellService.aidl`: proceso con permisos de shell que arranca Shizuku.
  La versión del servicio (en `ShellClient`) se sube cada vez que cambia el AIDL (ahora 4).
- `Hud.kt`, `HudDrawables.kt`, `SpaceViews.kt`, `UiKit.kt`: tema espacial (colores, fuentes,
  marcos neón, fondo de nebulosa con estrellas, radar animado).

## Créditos

- Fuentes Orbitron y Rajdhani (SIL Open Font License), ver `licencias/`.
- Iconos de Material Design Icons (Pictogrammers, Apache 2.0), ver `licencias/`.

## Limitaciones conocidas

- Los archivos de CPU/GPU/temperatura cambian según el fabricante; si un dato no existe en tu
  teléfono, aparece "—".
- Si el juego usa solo UDP sin conexión fija, Android no deja ver su servidor sin root; en ese
  caso se mide el servidor TCP del juego o internet, y la barra lo indica (SRV/APP/NET).
- La red en tiempo real por app no se puede leer sin root; la pestaña "Datos hoy" usa las
  estadísticas que guarda Android.
- La batería por app es el cálculo de Android (`dumpsys batterystats`).
