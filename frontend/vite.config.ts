import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    allowedHosts: ['localhost', '127.0.0.1', '5173-01m17kk0ctxf49jxpa8svyymv9.cloudspaces.litng.ai', 'brandsmith-seven.vercel.app'],
    proxy: {
      '/api': process.env.API_PROXY || 'http://localhost:8080',
    },
  },
  preview: {
    host: true,
    port: 5173,
  },
})
