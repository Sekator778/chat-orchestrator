import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.VITE_API_PROXY_TARGET || 'http://localhost:8099'

  return {
    plugins: [react()],
    server: {
      port: 4173,
      proxy: {
        '/api': {
          target,
          changeOrigin: true,
        },
      },
    },
    preview: {
      port: 4173,
    },
  }
})
