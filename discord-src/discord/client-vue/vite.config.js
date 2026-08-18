import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 开发服务器代理：把 /api 和 /ws 转发到后端 8091
// 这样开发时不需要处理跨域
const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8090'
const EMU_BASE_URL = process.env.EMU_BASE_URL || 'http://localhost:8090'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
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
        target: EMU_BASE_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/emu-api/, '/api/emu')
      },
      '/emu-ws': {
        target: EMU_BASE_URL,
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/emu-ws/, '/ws')
      },
      '/emu-static': {
        target: 'http://localhost:5273',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/emu-static/, '')
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2048
  }
})
