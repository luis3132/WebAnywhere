import type { Shortcut } from "../lib/shortcuts";
import { go } from "../lib/url";

interface Props {
  shortcuts: Shortcut[];
}

/**
 * Rejilla de accesos directos. Cada botón cambia la URL del navegador al
 * servicio correspondiente (no hace de proxy ni intermediario).
 */
export default function ShortcutGrid({ shortcuts }: Props) {
  return (
    <div className="grid grid-cols-3 gap-3 sm:grid-cols-4 md:grid-cols-5">
      {shortcuts.map((s) => (
        <button
          key={s.name}
          type="button"
          onClick={() => go(s.url)}
          title={`Ir a ${s.name}`}
          className="group flex aspect-square flex-col items-center justify-center gap-2 rounded-2xl border border-black/5 bg-white/70 p-3 text-center shadow-sm backdrop-blur transition hover:-translate-y-0.5 hover:shadow-md focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 dark:border-white/10 dark:bg-white/5"
        >
          <span
            className="flex h-11 w-11 items-center justify-center rounded-xl text-sm font-bold text-white shadow-inner"
            style={{ backgroundColor: s.color }}
          >
            {s.glyph}
          </span>
          <span className="text-xs font-medium text-gray-700 dark:text-gray-200">
            {s.name}
          </span>
        </button>
      ))}
    </div>
  );
}
