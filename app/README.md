# WebAnywhere Streamer

App Android que captura la pantalla del móvil, la codifica en tiempo real y la
sirve por HTTP en la red local, para reproducirla en el navegador de un sistema
de infoentretenimiento. El diseño y el porqué de cada decisión están en
[`../PLAN.md`](../PLAN.md).

El servidor entrega **los datos y la interfaz**: en el coche solo se teclea la
dirección y aparece un reproductor. No hay nada que instalar en el vehículo ni
nada publicado en internet.

## Requisitos

### En el móvil

| | Mínimo | Recomendado |
|---|---|---|
| Android | 8.0 (API 26) | 10 (API 29) o superior |
| RAM del dispositivo | 2 GB | 3 GB o más |
| Codificador | H.264 por hardware | — |
| Red | Wi-Fi compartida o hotspot | 5 GHz |

**Android 10 no es capricho: por debajo no hay audio.** `AudioPlaybackCapture`
es API 29. En 8.0 y 9.0 el vídeo funciona igual y el audio sencillamente no
existe — el motor lo trata como un extra y su ausencia no tumba la transmisión.

El codificador H.264 por hardware lo tiene cualquier teléfono de esta década.
Sin él, `MediaCodec` cae a un codificador por software que no sostiene ni 720p30
y calienta el doble.

### Memoria que usa la app

Medido con `dumpsys meminfo` en un Redmi Note 10 (Android 12):

| Estado | PSS |
|---|---|
| Interfaz abierta, sin transmitir | **~60 MB** |
| Transmitiendo, ajustes por defecto | ~110–130 MB (estimado) |
| Techo con buffer de 5 s y bitrate sin límite | ~180 MB (estimado) |

Sólo la primera fila está medida. Las otras dos son el baseline medido más lo
que el código reserva, que sí es determinista:

- **Ventana de segmentos de vídeo**: `bitrate ÷ 8 × buffer × 2,5`, acotada entre
  2 y **48 MB**. Con los ajustes por defecto (1080p60, calidad Alta, buffer 2 s)
  salen ~16 MB.
- **Ventana de audio**: `bitrate ÷ 8 × buffer × 3`, mínimo 256 KB. Nunca pasa de
  ~1 MB.
- **Codificador y `VirtualDisplay`**: memoria gráfica del sistema, no del montón
  de la app. Depende del chip.

El tope de bytes es el que manda, no el número de segmentos: con el bitrate sin
límite un segmento puede ser diez veces su tamaño habitual, y contar segmentos
se convertiría en decenas de megas sin avisar. Nada llega nunca al disco.

No hay fuga: la ventana descarta lo viejo al añadir lo nuevo, y el reproductor
suelta cada 500 ms lo que quedó más de 3 s por detrás del cabezal.

### En el coche

| Perfil | Requisito |
|---|---|
| **A** (fMP4 + MSE) | Navegador con `MediaSource` y decodificador H.264 — en la práctica, uno que reproduzca YouTube web |
| **B** (MJPEG) | Cualquier cosa que renderice HTML |

No hace falta instalar nada ni tener internet: sólo estar en la misma red que el
móvil. Si dudas de qué soporta el navegador del vehículo, abre `/probe` desde él
y la propia app te muestra la respuesta.

## Stack

| Pieza | Elección |
|---|---|
| Lenguaje / UI | Kotlin 2.3 + Jetpack Compose |
| Captura | `MediaProjection` → `VirtualDisplay` |
| Vídeo | `MediaCodec` H.264 por hardware, entrada por `Surface` (zero-copy) |
| Audio | `AudioPlaybackCapture` + AAC-LC (API 29+) |
| Contenedor | Muxer fMP4 escrito a mano (`mux/`) |
| Servidor | Ktor 3 CIO |
| Cliente | HTML/JS **ES5**, sin build, embebido en `assets/` |

`minSdk 26`, `compileSdk 36`, AGP 8.11, Gradle 8.14.3.

## Perfiles de entrega

Un solo encoder, varios formatos a la vez. El cliente elige el mejor que soporte.

| Perfil | Ruta | Requiere | Latencia | Audio |
|---|---|---|---|---|
| **A** (principal) | `/v/init.mp4` + `/v/seg/N.m4s` | MSE + XHR2 | 150 ms – 5 s, ajustable | sí |
| **A'** | `/live.m3u8` | HLS nativo | ~3 s | sí |
| **B** (garantizado) | `/mjpeg` | nada | ~100 ms | no |

La latencia del perfil A la fija el usuario desde la app con dos ajustes que se
suelen confundir:

- **Latencia** (100 / 250 / 500 ms) es la longitud del segmento, o sea cuánto
  espera un fotograma antes de poder salir. Más corto es más peticiones por
  segundo, y un WebView de coche puede no dar abasto.
- **Buffer** (150 ms – 5 s) es el colchón que guarda el reproductor. Absorbe los
  fotogramas que llegan tarde; el reproductor lo mantiene acelerando o frenando
  un 4–8 %, nunca saltando.

El buffer no puede bajar de **1,5 segmentos**: menos que eso es medio que nadie
guarda, es decir perdido. La app bloquea las opciones que la latencia elegida
haga imposibles.

El perfil B es el seguro de vida: un `<img src="/mjpeg">`, cero JavaScript, cero
negociación de codecs. Funciona en cualquier cosa que renderice HTML y además es
inmune a las políticas de autoplay que bloquean `<video>`.

> **En Android 14 o superior, A y B no pueden correr a la vez.** Un permiso de
> captura concede exactamente un `VirtualDisplay` — la segunda llamada a
> `createVirtualDisplay` lanza `SecurityException` — y cada perfil necesita el
> suyo porque graban a resoluciones distintas. El que llegue primero se lo queda;
> el segundo cliente ve un aviso en la app. Al girar el móvil no se pide otro:
> se reapunta el que ya hay con `resize` + `setSurface`.

## Rutas

```
GET  /             reproductor (autodetecta perfil)
GET  /?p=mjpeg     fuerza perfil B          GET /?p=mse   fuerza perfil A
GET  /?audio=0     desactiva el audio       GET /?hud=1   muestra buffer y perfil
GET  /?stats=1     abre el panel de telemetría (FPS, buffer, red, encoder)
GET  /?buffer=3000 fuerza el buffer en ms   GET /?statsms=5000  refresco del panel
GET  /probe        diagnóstico de capacidades del navegador
POST /report       el navegador reporta UA / MSE / codecs
GET  /status       estado en JSON
GET  /v/live.json  índice del vídeo (ready, codec, latest, oldest)
GET  /v/init.mp4   segmento de inicialización fMP4
GET  /v/seg/{n}.m4s  segmento de medios (long-poll si aún no existe)
GET  /a/…          idéntico, para la pista de audio
GET  /live.m3u8    manifiesto HLS sobre los mismos segmentos
GET  /mjpeg        multipart/x-mixed-replace
```

## Compilar y ejecutar

```bash
cd app
./gradlew :streamer:assembleDebug          # APK de depuración
./gradlew :streamer:testDebugUnitTest      # tests (incluye validación del muxer)
./gradlew :streamer:installDebug           # instalar en un dispositivo conectado
```

`local.properties` se genera con la ruta del SDK y está en `.gitignore`.

### Compilar firmado

Sin llave, `assembleRelease` produce un APK **sin firmar**, que Android no
instala. Para firmarlo:

```bash
keytool -genkeypair -v -keystore webanywhere.jks -alias webanywhere \
        -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # y rellenar las contraseñas
./gradlew :streamer:assembleRelease
```

El nombre del archivo pasa de `-unsigned.apk` a `-release.apk` en cuanto detecta
la llave: es la forma de confirmar que la cogió. Las credenciales se leen de
`keystore.properties` o, si no existe, de `ANDROID_KEYSTORE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` y `ANDROID_KEY_PASSWORD` — para
CI, que no tiene dónde poner un archivo. Sin ninguna de las dos, la compilación
no falla: sale sin firmar, para que un clon nuevo no se rompa por no tener una
llave que nunca podría haber tenido.

Se firma con v1 **además** de v2: un WebView de coche puede correr sobre
Android 6, y ese no sabe verificar una firma solo-v2 — rechaza la instalación
sin explicar por qué.

Guarda copia del `.jks` y de las contraseñas fuera del repo. Android identifica
una app por su firma: si la pierdes, la siguiente versión cuenta como una app
distinta y hay que desinstalar la anterior a mano, desde el coche y sin adb.

En el móvil: pulsar **Iniciar transmisión**, conceder el permiso de captura, y
abrir en el coche la dirección que muestra la app (hay un QR, porque teclear una
IP en una pantalla táctil de coche es un suplicio).

## Estado de verificación

Lo que está **comprobado en esta máquina**:

- Compila en debug y en release (R8 incluido).
- 25 tests unitarios en verde, ninguno saltado.
- **El muxer fMP4 está validado de extremo a extremo**: el test genera H.264 real
  con ffmpeg, lo pasa por `Fmp4Track`, y luego hace que `ffprobe` y `ffmpeg`
  decodifiquen el resultado. Comprueba codec, resolución, que el número de
  fotogramas decodificados coincida con el muxeado, y que el decodificador no
  emita ni una queja. Un muxer que solo compila no demuestra nada: el modo de
  fallo típico es un stream verosímil que todos los reproductores rechazan.
- La invariante de contrapresión tiene tests propios: publicar nunca se bloquea
  por un cliente lento, y un cliente atascado no le roba fotogramas a uno sano.

Lo que **necesita un dispositivo real** y no se ha podido probar aquí:

- Todo el camino de `MediaProjection`, `MediaCodec` y `AudioPlaybackCapture`
  (requieren hardware Android).
- El reproductor ES5 contra el WebView del vehículo. **Este es el siguiente
  paso**: instalar, abrir `/probe` desde el coche y anotar qué devuelve.
- La sincronía audio/vídeo. Es la parte menos ejercitada: ambas pistas comparten
  la línea de tiempo por construcción (`tfdt` absoluto, `mode='segments'`), pero
  no está medida. Si suena desincronizado, `/?audio=0` deja el vídeo limpio.

## Estructura

```
streamer/src/main/
├── assets/
│   ├── player.html       reproductor ES5 (perfil A con caída a B)
│   └── probe.html        diagnóstico de capacidades
└── java/com/webanywhere/streamer/
    ├── Config.kt              parámetros de captura y codificación
    ├── StreamHub.kt           estado compartido engine ↔ servidor ↔ UI
    ├── MainActivity.kt        permisos y consentimiento de captura
    ├── ui/                    pantalla Compose (URL, QR, stats, diagnósticos)
    ├── service/               foreground service + wake locks + wifi lock
    ├── engine/                orquestación y arranque por demanda
    ├── capture/               VirtualDisplay y pipeline JPEG
    ├── encode/                MediaCodec H.264 y AAC
    ├── mux/                   fMP4 escrito a mano (Boxes, AnnexB, Fmp4, Fmp4Track)
    ├── stream/                ventana de segmentos y fan-out MJPEG
    ├── server/                rutas Ktor
    └── net/                   detección de IP y QR
```

## Dos cosas que conviene saber

**DRM.** Netflix, Disney+, Prime Video y Max salen en **negro** bajo
`MediaProjection`, y su audio tampoco se captura. Es una protección de hardware
(Widevine L1 + superficies seguras) sin solución legítima. **YouTube sí
funciona.** Los archivos locales no sufren esto.

**Calor.** Codificar de forma continua con el Wi-Fi activo calienta, y el
throttling térmico tumba el framerate a los ~15 minutos. Por eso el motor
arranca cada pipeline solo cuando hay un cliente mirándolo y lo apaga 15 s
después del último.
