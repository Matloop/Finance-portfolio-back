// astro.config.mjs

import { defineConfig } from 'astro/config';
import react from "@astrojs/react";
import tailwind from "@astrojs/tailwind";

// https://astro.build/config
export default defineConfig({
  integrations: [react(), tailwind()],
  server: {
    host: 'localhost', // Garante que o servidor use 'localhost' em vez de '127.0.0.1' ou um IP de rede
    port: 3000,        // Define a porta padrão para 3000
  },
  vite: {
    ssr: {
      noExternal: ['gsap']
    }
  }
});