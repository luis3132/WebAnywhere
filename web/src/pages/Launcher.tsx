import { useState, type FormEvent } from "react";
import { Button, Input } from "neogestify-ui-components";
import { ThemeToggle } from "neogestify-ui-components/theme";
import { AlertaError } from "neogestify-ui-components/alerts";
import { ArrowRightIcon, NetworkIcon } from "neogestify-ui-components/icons";
import ShortcutGrid from "../components/ShortcutGrid";
import { SHORTCUTS } from "../lib/shortcuts";
import { go, normalizeUrl } from "../lib/url";

export default function Launcher() {
  const [value, setValue] = useState("");

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const url = normalizeUrl(value);
    if (!url) {
      AlertaError(
        "URL no válida",
        "Escribe una dirección como netflix.com, 192.168.1.10:8080 o https://ejemplo.com",
      );
      return;
    }
    go(url);
  }

  return (
    <main className="relative flex min-h-full flex-col items-center bg-gradient-to-b from-slate-100 to-slate-200 px-4 py-8 text-gray-900 dark:from-slate-950 dark:to-slate-900 dark:text-gray-100">
      <div className="absolute right-4 top-4">
        <ThemeToggle />
      </div>

      <div className="flex w-full max-w-2xl flex-1 flex-col justify-center gap-8 py-8">
        <header className="text-center">
          <h1 className="flex items-center justify-center gap-2 text-3xl font-extrabold tracking-tight sm:text-4xl">
            <NetworkIcon className="h-8 w-8" />
            WebAnywhere
          </h1>
          <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">
            Escribe cualquier URL y ve a donde quieras. Ideal para navegadores
            bloqueados en sistemas de entretenimiento de vehículos.
          </p>
        </header>

        <form onSubmit={handleSubmit} className="flex flex-col gap-3 sm:flex-row">
          <div className="flex-1">
            <Input
              type="text"
              inputMode="url"
              autoFocus
              value={value}
              onChange={(e) => setValue(e.target.value)}
              onClear={() => setValue("")}
              clearable
              placeholder="netflix.com  ·  192.168.1.10:8080  ·  https://ejemplo.com"
              aria-label="Dirección web"
              size="lg"
            />
          </div>
          <Button
            type="submit"
            variant="primary"
            size="lg"
            rightIcon={<ArrowRightIcon className="h-5 w-5" />}
          >
            Ir
          </Button>
        </form>

        <section>
          <h2 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-500 dark:text-gray-400">
            Accesos directos
          </h2>
          <ShortcutGrid shortcuts={SHORTCUTS} />
        </section>
      </div>

      <footer className="pb-2 text-center text-xs text-gray-400">
        WebAnywhere · redirige sin intermediarios
      </footer>
    </main>
  );
}
