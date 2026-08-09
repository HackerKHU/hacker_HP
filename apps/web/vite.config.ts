import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    // API base URL이 /api/v1 고정이라 로컬 dev 서버에는 받을 것이 없다(운영은 Vercel rewrites가 받는다).
    // 이 프록시가 없으면 npm run dev에서 모든 API 호출이 5173의 404로 떨어진다.
    // 키는 /api/v1이 아니라 /api로 둔다 — 접두사 매칭이라 v2가 생겨도 그대로 넘어간다.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
