import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:9090'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5175,
    strictPort: true,
    host: '0.0.0.0',
    proxy: {
      '/api': {
        target: API_BASE_URL,
        changeOrigin: true,
        ws: true
      },
      '/ws': {
        target: API_BASE_URL,
        changeOrigin: true,
        ws: true
      },
      '/emu-api': {
        target: API_BASE_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/emu-api/, '/api/emu')
      },
      '/emu-ws': {
        target: API_BASE_URL,
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/emu-ws/, '/ws')
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2048
  }
})
