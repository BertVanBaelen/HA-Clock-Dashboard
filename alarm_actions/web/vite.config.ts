import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The panel is served below the ingress path (/api/hassio_ingress/<token>/), so every URL must
// be relative.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
