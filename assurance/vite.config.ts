import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

const cardModules = fileURLToPath(
  new URL("../src/app/cards/node_modules", import.meta.url),
);

export default defineConfig({
  resolve: {
    alias: [
      {
        find: /^@hubspot\/ui-extensions\/testing$/,
        replacement: `${cardModules}/@hubspot/ui-extensions/dist/testing/index.js`,
      },
      {
        find: /^@hubspot\/ui-extensions$/,
        replacement: `${cardModules}/@hubspot/ui-extensions/dist/index.js`,
      },
      { find: /^react\/jsx-runtime$/, replacement: `${cardModules}/react/jsx-runtime.js` },
      { find: /^react\/jsx-dev-runtime$/, replacement: `${cardModules}/react/jsx-dev-runtime.js` },
      { find: /^react$/, replacement: `${cardModules}/react/index.js` },
    ],
    dedupe: ["react", "@hubspot/ui-extensions"],
  },
  server: {
    fs: { allow: [fileURLToPath(new URL("..", import.meta.url))] },
  },
});
