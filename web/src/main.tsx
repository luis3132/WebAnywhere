import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createHashRouter, RouterProvider } from "react-router";
import { ThemeProvider } from "neogestify-ui-components/theme";
import "./index.css";
import Launcher from "./pages/Launcher";

// HashRouter: funciona sin configuración de servidor (ideal para GitHub Pages,
// donde un refresh en rutas normales daría 404).
const router = createHashRouter([
  {
    path: "/",
    element: <Launcher />,
  },
]);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider defaultTheme="dark" enableSystem>
      <RouterProvider router={router} />
    </ThemeProvider>
  </StrictMode>,
);
