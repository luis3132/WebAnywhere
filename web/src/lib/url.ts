/**
 * Normaliza una URL "cruda" escrita por el usuario para poder redirigir.
 *
 * Reglas:
 *  - Si ya trae esquema (http:// o https:// u otro), se respeta.
 *  - Si empieza por "//", se le antepone https:.
 *  - Si parece un host/URL sin esquema (tiene punto o es localhost/IP:puerto),
 *    se le antepone https:// por defecto.
 *  - Si no parece un host, se trata como búsqueda (opcional) — aquí devolvemos
 *    null para que la UI muestre un error.
 */
export function normalizeUrl(raw: string): string | null {
  const input = raw.trim();
  if (!input) return null;

  // Ya trae un esquema explícito (http, https, ftp, etc.)
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(input)) {
    return input;
  }

  // Protocol-relative //example.com
  if (input.startsWith("//")) {
    return `https:${input}`;
  }

  // localhost, IPs o hosts con punto -> asumimos host web sin esquema.
  const looksLikeHost =
    input.includes(".") ||
    /^localhost(:\d+)?(\/.*)?$/i.test(input) ||
    /^\d{1,3}(\.\d{1,3}){3}(:\d+)?/.test(input);

  if (looksLikeHost && !/\s/.test(input)) {
    return `https://${input}`;
  }

  return null;
}

/**
 * Devuelve la versión http:// de una URL https:// (y viceversa deja igual si
 * ya es http). Útil como fallback cuando el destino no tiene certificado.
 */
export function toHttp(url: string): string {
  return url.replace(/^https:\/\//i, "http://");
}

/** Redirige la pestaña actual a la URL indicada. */
export function go(url: string): void {
  window.location.href = url;
}
