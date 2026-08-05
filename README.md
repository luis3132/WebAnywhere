# WebAnywhere

Ver contenido del móvil en la pantalla de un coche, sin instalar nada en el coche.

El repositorio contiene **dos intentos del mismo problema**, uno mucho más
ambicioso que el otro. Conviven a propósito: resuelven casos distintos y fallan
por motivos distintos.

| | Qué hace | Estado |
|---|---|---|
| [`web/`](web) | Un lanzador que redirige el navegador del coche a Netflix, YouTube, etc. | Funciona, es diminuto |
| [`app/`](app) | App Android que transmite la pantalla del móvil por la red local | Donde está el trabajo real |

## El problema

El navegador de un sistema de infoentretenimiento suele ser un WebView viejo,
con teclado táctil incómodo y sin forma de instalar nada. Dos caminos:

**Llevarle una URL buena** — eso es `web/`. Cero infraestructura, pero el
contenido sigue dependiendo de que ese navegador sepa reproducirlo, y muchos no
saben.

**Llevarle la pantalla del móvil ya decodificada** — eso es `app/`. El móvil
captura, codifica en H.264 y sirve por HTTP en la red local; en el coche solo se
teclea una IP. Funciona con cualquier cosa que el móvil pueda mostrar, pero hay
que construir un pipeline de vídeo entero.

## `web/` — el lanzador

SPA de React 19 + Vite + Tailwind 4. Una rejilla de accesos directos y un campo
para escribir una URL a mano, con `normalizeUrl()` para completar lo que falte
(`youtube.com` → `https://youtube.com`).

No hace de intermediario ni proxy: cambia `window.location`. Usa `HashRouter`
porque está pensada para GitHub Pages, donde recargar una ruta normal daría 404.
Sin imágenes externas — los "logos" son letras y emojis, para que el bundle sea
mínimo.

```bash
cd web
bun install     # o npm
bun run dev
```

> `src/App.tsx` sigue siendo la plantilla de Vite y no se usa: el punto de
> entrada real es `src/main.tsx` → `src/pages/Launcher.tsx`.

## `app/` — el transmisor

App Android en Kotlin + Compose que captura la pantalla con `MediaProjection`,
la codifica con `MediaCodec` por hardware y la sirve con un servidor Ktor
embebido. **El mismo servidor entrega los datos y el reproductor**: en el coche
se abre la dirección y aparece un `<video>` funcionando.

El transporte es el mismo que usa YouTube web — segmentos fMP4 inyectados por
MSE — porque es lo que un navegador viejo sabe hacer aunque no entienda HLS ni
DASH. Con un perfil MJPEG de reserva que funciona en cualquier cosa que pinte
HTML.

```bash
cd app
./gradlew :streamer:installDebug
```

Detalles completos en [`app/README.md`](app/README.md). El diseño y el porqué de
cada decisión, en [`PLAN.md`](PLAN.md).

## Lo que conviene saber antes de esperar demasiado

**DRM.** Netflix, Disney+, Prime Video y Max salen en **negro** bajo
`MediaProjection`, y su audio tampoco se captura. Es protección por hardware
(Widevine L1 + superficies seguras), sin solución legítima. YouTube sí funciona.

Esto define el reparto entre las dos mitades más que ninguna decisión técnica:
los servicios que `web/` enlaza son en su mayoría los que `app/` **no** puede
retransmitir. No se sustituyen — se complementan por donde el otro no llega.

**Bloqueo en movimiento.** Muchos infoentretenimientos bloquean el vídeo si el
coche no está en P. No se puede evitar desde este lado, y es lo correcto.

**Calor.** Codificar de forma continua con el Wi-Fi activo calienta, y el
throttling térmico tumba el framerate a los ~15 minutos.

## Estructura

```
.
├── web/          lanzador React (redirige, no transmite)
├── app/          proyecto Gradle de la app Android
│   └── streamer/ único módulo: captura, codificación, muxer, servidor y UI
├── PLAN.md       diseño, alternativas descartadas y orden de construcción
└── LICENSE       MIT
```

Los dos proyectos son independientes: no comparten código ni build. El cliente
HTML de `app/` está escrito en **ES5 a mano** precisamente porque no puede
parecerse en nada a `web/` — tiene que correr en WebViews que React 19 ni
arrancaría.

## Licencia

MIT — ver [LICENSE](LICENSE).
