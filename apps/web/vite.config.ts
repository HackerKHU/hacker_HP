import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import { SITE_ORIGIN } from './site.config.ts'

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    {
      // 링크 미리보기 봇은 대부분 **절대 URL**을 요구한다. 상대경로면 이미지를 못 읽어
      // 미리보기에 그림이 안 뜬다. 빌드·개발 양쪽에서 같은 값으로 치환한다.
      name: 'inject-site-origin',
      transformIndexHtml: (html: string) =>
        html.replaceAll('%SITE_ORIGIN%', SITE_ORIGIN),
    },
  ],
  resolve: {
    // shadcn/ui가 생성하는 컴포넌트가 @/ 별칭을 쓴다. tsconfig.app.json의 paths와 같이 맞춘다.
    alias: { '@': path.resolve(import.meta.dirname, './src') },
  },
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
