// vite.config.js
import { defineConfig } from "file:///Users/ren/Library/Application%20Support/TRAE%20SOLO%20CN/ModularData/ai-agent/work-mode-projects/6a6d8114a6113204564fb48e/discord-src/discord/client-vue/node_modules/vite/dist/node/index.js";
import vue from "file:///Users/ren/Library/Application%20Support/TRAE%20SOLO%20CN/ModularData/ai-agent/work-mode-projects/6a6d8114a6113204564fb48e/discord-src/discord/client-vue/node_modules/@vitejs/plugin-vue/dist/index.mjs";
import { fileURLToPath, URL } from "node:url";
var __vite_injected_original_import_meta_url = "file:///Users/ren/Library/Application%20Support/TRAE%20SOLO%20CN/ModularData/ai-agent/work-mode-projects/6a6d8114a6113204564fb48e/discord-src/discord/client-vue/vite.config.js";
var API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8090";
var EMU_BASE_URL = process.env.EMU_BASE_URL || "http://localhost:8088";
var vite_config_default = defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", __vite_injected_original_import_meta_url))
    }
  },
  server: {
    port: 5173,
    strictPort: true,
    host: "0.0.0.0",
    proxy: {
      "/api": {
        target: API_BASE_URL,
        changeOrigin: true,
        ws: true
      },
      "/ws": {
        target: API_BASE_URL,
        changeOrigin: true,
        ws: true
      },
      "/emu-api": {
        target: EMU_BASE_URL,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/emu-api/, "/api")
      },
      "/emu-ws": {
        target: EMU_BASE_URL,
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/emu-ws/, "/ws")
      },
      "/emu-static": {
        target: "http://localhost:5273",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/emu-static/, "")
      }
    }
  },
  build: {
    outDir: "dist",
    emptyOutDir: true,
    chunkSizeWarningLimit: 2048
  }
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcuanMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCIvVXNlcnMvcmVuL0xpYnJhcnkvQXBwbGljYXRpb24gU3VwcG9ydC9UUkFFIFNPTE8gQ04vTW9kdWxhckRhdGEvYWktYWdlbnQvd29yay1tb2RlLXByb2plY3RzLzZhNmQ4MTE0YTYxMTMyMDQ1NjRmYjQ4ZS9kaXNjb3JkLXNyYy9kaXNjb3JkL2NsaWVudC12dWVcIjtjb25zdCBfX3ZpdGVfaW5qZWN0ZWRfb3JpZ2luYWxfZmlsZW5hbWUgPSBcIi9Vc2Vycy9yZW4vTGlicmFyeS9BcHBsaWNhdGlvbiBTdXBwb3J0L1RSQUUgU09MTyBDTi9Nb2R1bGFyRGF0YS9haS1hZ2VudC93b3JrLW1vZGUtcHJvamVjdHMvNmE2ZDgxMTRhNjExMzIwNDU2NGZiNDhlL2Rpc2NvcmQtc3JjL2Rpc2NvcmQvY2xpZW50LXZ1ZS92aXRlLmNvbmZpZy5qc1wiO2NvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9pbXBvcnRfbWV0YV91cmwgPSBcImZpbGU6Ly8vVXNlcnMvcmVuL0xpYnJhcnkvQXBwbGljYXRpb24lMjBTdXBwb3J0L1RSQUUlMjBTT0xPJTIwQ04vTW9kdWxhckRhdGEvYWktYWdlbnQvd29yay1tb2RlLXByb2plY3RzLzZhNmQ4MTE0YTYxMTMyMDQ1NjRmYjQ4ZS9kaXNjb3JkLXNyYy9kaXNjb3JkL2NsaWVudC12dWUvdml0ZS5jb25maWcuanNcIjtpbXBvcnQgeyBkZWZpbmVDb25maWcgfSBmcm9tICd2aXRlJ1xuaW1wb3J0IHZ1ZSBmcm9tICdAdml0ZWpzL3BsdWdpbi12dWUnXG5pbXBvcnQgeyBmaWxlVVJMVG9QYXRoLCBVUkwgfSBmcm9tICdub2RlOnVybCdcblxuLy8gVml0ZSBcdTVGMDBcdTUzRDFcdTY3MERcdTUyQTFcdTU2NjhcdTRFRTNcdTc0MDZcdUZGMUFcdTYyOEEgL2FwaSBcdTU0OEMgL3dzIFx1OEY2Q1x1NTNEMVx1NTIzMFx1NTQwRVx1N0FFRiA4MDkxXG4vLyBcdThGRDlcdTY4MzdcdTVGMDBcdTUzRDFcdTY1RjZcdTRFMERcdTk3MDBcdTg5ODFcdTU5MDRcdTc0MDZcdThERThcdTU3REZcbmNvbnN0IEFQSV9CQVNFX1VSTCA9IHByb2Nlc3MuZW52LkFQSV9CQVNFX1VSTCB8fCAnaHR0cDovL2xvY2FsaG9zdDo4MDkwJ1xuY29uc3QgRU1VX0JBU0VfVVJMID0gcHJvY2Vzcy5lbnYuRU1VX0JBU0VfVVJMIHx8ICdodHRwOi8vbG9jYWxob3N0OjgwODgnXG5cbmV4cG9ydCBkZWZhdWx0IGRlZmluZUNvbmZpZyh7XG4gIHBsdWdpbnM6IFt2dWUoKV0sXG4gIHJlc29sdmU6IHtcbiAgICBhbGlhczoge1xuICAgICAgJ0AnOiBmaWxlVVJMVG9QYXRoKG5ldyBVUkwoJy4vc3JjJywgaW1wb3J0Lm1ldGEudXJsKSlcbiAgICB9XG4gIH0sXG4gIHNlcnZlcjoge1xuICAgIHBvcnQ6IDUxNzMsXG4gICAgc3RyaWN0UG9ydDogdHJ1ZSxcbiAgICBob3N0OiAnMC4wLjAuMCcsXG4gICAgcHJveHk6IHtcbiAgICAgICcvYXBpJzoge1xuICAgICAgICB0YXJnZXQ6IEFQSV9CQVNFX1VSTCxcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxuICAgICAgICB3czogdHJ1ZVxuICAgICAgfSxcbiAgICAgICcvd3MnOiB7XG4gICAgICAgIHRhcmdldDogQVBJX0JBU0VfVVJMLFxuICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWUsXG4gICAgICAgIHdzOiB0cnVlXG4gICAgICB9LFxuICAgICAgJy9lbXUtYXBpJzoge1xuICAgICAgICB0YXJnZXQ6IEVNVV9CQVNFX1VSTCxcbiAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxuICAgICAgICByZXdyaXRlOiAocGF0aCkgPT4gcGF0aC5yZXBsYWNlKC9eXFwvZW11LWFwaS8sICcvYXBpJylcbiAgICAgIH0sXG4gICAgICAnL2VtdS13cyc6IHtcbiAgICAgICAgdGFyZ2V0OiBFTVVfQkFTRV9VUkwsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgICAgd3M6IHRydWUsXG4gICAgICAgIHJld3JpdGU6IChwYXRoKSA9PiBwYXRoLnJlcGxhY2UoL15cXC9lbXUtd3MvLCAnL3dzJylcbiAgICAgIH0sXG4gICAgICAnL2VtdS1zdGF0aWMnOiB7XG4gICAgICAgIHRhcmdldDogJ2h0dHA6Ly9sb2NhbGhvc3Q6NTI3MycsXG4gICAgICAgIGNoYW5nZU9yaWdpbjogdHJ1ZSxcbiAgICAgICAgcmV3cml0ZTogKHBhdGgpID0+IHBhdGgucmVwbGFjZSgvXlxcL2VtdS1zdGF0aWMvLCAnJylcbiAgICAgIH1cbiAgICB9XG4gIH0sXG4gIGJ1aWxkOiB7XG4gICAgb3V0RGlyOiAnZGlzdCcsXG4gICAgZW1wdHlPdXREaXI6IHRydWUsXG4gICAgY2h1bmtTaXplV2FybmluZ0xpbWl0OiAyMDQ4XG4gIH1cbn0pXG4iXSwKICAibWFwcGluZ3MiOiAiO0FBQWluQixTQUFTLG9CQUFvQjtBQUM5b0IsT0FBTyxTQUFTO0FBQ2hCLFNBQVMsZUFBZSxXQUFXO0FBRjJXLElBQU0sMkNBQTJDO0FBTS9iLElBQU0sZUFBZSxRQUFRLElBQUksZ0JBQWdCO0FBQ2pELElBQU0sZUFBZSxRQUFRLElBQUksZ0JBQWdCO0FBRWpELElBQU8sc0JBQVEsYUFBYTtBQUFBLEVBQzFCLFNBQVMsQ0FBQyxJQUFJLENBQUM7QUFBQSxFQUNmLFNBQVM7QUFBQSxJQUNQLE9BQU87QUFBQSxNQUNMLEtBQUssY0FBYyxJQUFJLElBQUksU0FBUyx3Q0FBZSxDQUFDO0FBQUEsSUFDdEQ7QUFBQSxFQUNGO0FBQUEsRUFDQSxRQUFRO0FBQUEsSUFDTixNQUFNO0FBQUEsSUFDTixZQUFZO0FBQUEsSUFDWixNQUFNO0FBQUEsSUFDTixPQUFPO0FBQUEsTUFDTCxRQUFRO0FBQUEsUUFDTixRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsUUFDZCxJQUFJO0FBQUEsTUFDTjtBQUFBLE1BQ0EsT0FBTztBQUFBLFFBQ0wsUUFBUTtBQUFBLFFBQ1IsY0FBYztBQUFBLFFBQ2QsSUFBSTtBQUFBLE1BQ047QUFBQSxNQUNBLFlBQVk7QUFBQSxRQUNWLFFBQVE7QUFBQSxRQUNSLGNBQWM7QUFBQSxRQUNkLFNBQVMsQ0FBQyxTQUFTLEtBQUssUUFBUSxjQUFjLE1BQU07QUFBQSxNQUN0RDtBQUFBLE1BQ0EsV0FBVztBQUFBLFFBQ1QsUUFBUTtBQUFBLFFBQ1IsY0FBYztBQUFBLFFBQ2QsSUFBSTtBQUFBLFFBQ0osU0FBUyxDQUFDLFNBQVMsS0FBSyxRQUFRLGFBQWEsS0FBSztBQUFBLE1BQ3BEO0FBQUEsTUFDQSxlQUFlO0FBQUEsUUFDYixRQUFRO0FBQUEsUUFDUixjQUFjO0FBQUEsUUFDZCxTQUFTLENBQUMsU0FBUyxLQUFLLFFBQVEsaUJBQWlCLEVBQUU7QUFBQSxNQUNyRDtBQUFBLElBQ0Y7QUFBQSxFQUNGO0FBQUEsRUFDQSxPQUFPO0FBQUEsSUFDTCxRQUFRO0FBQUEsSUFDUixhQUFhO0FBQUEsSUFDYix1QkFBdUI7QUFBQSxFQUN6QjtBQUNGLENBQUM7IiwKICAibmFtZXMiOiBbXQp9Cg==
