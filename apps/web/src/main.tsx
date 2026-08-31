import './index.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.tsx'
import { SessionProvider } from './auth/session.tsx'
import { LiveAlertProvider } from './components/live-alert/LiveAlertProvider.tsx'

const rootElement = document.getElementById('root')

if (!rootElement) {
  throw new Error('React root element를 찾을 수 없습니다.')
}

createRoot(rootElement).render(
  <StrictMode>
    <BrowserRouter>
      <LiveAlertProvider>
        <SessionProvider>
          <App />
        </SessionProvider>
      </LiveAlertProvider>
    </BrowserRouter>
  </StrictMode>,
)
