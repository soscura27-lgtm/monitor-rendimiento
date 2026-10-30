# Reglas de R8 para la versión release.
# Room, WorkManager, OkHttp y Media3 incluyen sus propias reglas de consumo.

# jsoup referencia re2j de forma opcional; no está en el classpath y no hace falta.
-dontwarn com.google.re2j.**
-dontwarn org.slf4j.**
