export interface Shortcut {
  name: string;
  url: string;
  /** Color de fondo de la tarjeta (marca). */
  color: string;
  /** Texto/emoji corto que se muestra como "logo" para no cargar imágenes. */
  glyph: string;
}

/**
 * Accesos directos a plataformas comunes. La página no hace de intermediario:
 * simplemente cambia la URL del navegador a la del servicio.
 *
 * Se mantiene sin imágenes externas para que el bundle siga siendo mínimo
 * (pensado para GitHub Pages).
 */
export const SHORTCUTS: Shortcut[] = [
  { name: "Netflix", url: "https://www.netflix.com", color: "#e50914", glyph: "N" },
  { name: "Disney+", url: "https://www.disneyplus.com", color: "#0c1a4b", glyph: "D+" },
  { name: "YouTube", url: "https://www.youtube.com", color: "#ff0000", glyph: "▶" },
  { name: "Prime Video", url: "https://www.primevideo.com", color: "#00a8e1", glyph: "prime" },
  { name: "Max", url: "https://www.max.com", color: "#0046ff", glyph: "MAX" },
  { name: "Spotify", url: "https://open.spotify.com", color: "#1db954", glyph: "♪" },
  { name: "Twitch", url: "https://www.twitch.tv", color: "#9146ff", glyph: "T" },
  { name: "Apple TV", url: "https://tv.apple.com", color: "#111111", glyph: "tv" },
  { name: "Crunchyroll", url: "https://www.crunchyroll.com", color: "#f47521", glyph: "CR" },
  { name: "Google", url: "https://www.google.com", color: "#4285f4", glyph: "G" },
];
