import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'

// The Spring Boot backend runs on :8080. Proxying keeps the browser same-origin,
// so no CORS changes are needed on the API.
const API = 'http://localhost:8080'

// The browser still sends its Origin header (e.g. http://localhost:5174 when 5173 is
// taken), which Spring's CORS check would reject. Through this proxy the request is
// same-origin, so drop the header and the UI works on whatever port Vite picks.
const toApi: ProxyOptions = {
  target: API,
  changeOrigin: true,
  configure: proxy => proxy.on('proxyReq', req => req.removeHeader('origin')),
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': toApi,
      '/actuator': toApi,
    },
  },
})
