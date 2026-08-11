import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8090'
const EMU_BASE_URL = process.env.EMU_BASE_URL || 'http://localhost:8088'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5174,
    strictPort: true,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: API_BASE_URL,
        changeOrigin: true,
        ws: true
      },
      '/emu-api': {
        target: EMU_BASE_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/emu-api/, '/api')
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2048
  }
})
