# Monitor de rendimiento

Barra flotante para Android con FPS, tiempo por fotograma, 1% low, CPU, GPU, RAM,
temperaturas, batería y una gráfica de FPS. Se dibuja encima de cualquier juego o app.

## Qué mide y qué necesita

| Dato | Necesita Shizuku |
| --- | --- |
| FPS, ms por fotograma, 1% low y gráfica de la app al frente | Sí |
| Uso de CPU (%) | Sí |
| Uso y frecuencia de GPU | Sí (solo chips Adreno; otros pueden salir con "—") |
| Frecuencia de CPU y temperaturas de CPU/GPU | Casi siempre sí |
| RAM, batería (%, temperatura, mA, W), estado térmico, Hz de pantalla | No |

Android no deja que una app normal vea los FPS de otras apps ni el uso de CPU. Por eso la app
usa [Shizuku](https://shizuku.rikka.app/), que da permisos de shell (como adb) sin root.
Los FPS se calculan con `dumpsys SurfaceFlinger --latency`, leyendo los tiempos en que cada
fotograma se presenta en pantalla.

## Cómo usarla

1. Instala la app y abre **Monitor de rendimiento**.
2. Toca **Permitir superposición** y activa el permiso.
3. Instala **Shizuku**, actívalo (depuración inalámbrica → emparejar → Iniciar) y toca
   **Dar permiso** en esta app. Hay que volver a iniciar Shizuku cada vez que se reinicia el teléfono.
4. Toca **Iniciar barra flotante**. Arrastra la barra donde quieras.
5. Para cerrarla usa el botón de la app o **Detener** en la notificación.

En Xiaomi/HyperOS, si Shizuku falla al emparejar, activa también "Depuración USB (ajustes de
seguridad)" en Opciones de desarrollador y pon el estilo de notificaciones en "Android".

## Cómo compilarla

Requiere JDK 17 y el SDK de Android (compileSdk 35).

```
./gradlew assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Estructura

- `MainActivity.kt`: pantalla de ajustes, permisos y estado de Shizuku.
- `OverlayService.kt`: servicio en primer plano que dibuja la barra y refresca cada segundo.
- `Sampler.kt`: reúne RAM, batería, CPU, GPU y temperaturas.
- `FpsEngine.kt`: calcula FPS y 1% low a partir de SurfaceFlinger.
- `ShellService.kt` + `IShellService.aidl`: proceso con permisos de shell que arranca Shizuku.
- `GraphView.kt`: gráfica del historial de FPS.

## Limitaciones conocidas

- Los archivos de CPU/GPU/temperatura cambian según el fabricante; si un dato no existe en tu
  teléfono, la barra muestra "—" en esa línea.
- La detección de la app al frente usa `dumpsys`, así que puede tardar hasta 3 segundos en
  notar un cambio de app.
- Esta versión no se ha probado en un dispositivo real.
