import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react'
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { PrivacyPage } from './PrivacyPage'
import { TermsPage } from './TermsPage'

const LIST_LOCATION = '/notices?page=3&status=ACTIVE&q=안내'
const LEGAL_PAGES = [
  { path: '/privacy', title: '개인정보처리방침' },
  { path: '/terms', title: '이용약관' },
]

let originalUrl: string
let originalState: unknown

function NoticeListEntry() {
  return (
    <>
      {LEGAL_PAGES.map(({ path, title }) => (
        <Link key={path} to={path}>
          {title}
        </Link>
      ))}
    </>
  )
}

function renderBrowserRouter() {
  render(
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<div>랜딩</div>} />
        <Route path="/notices" element={<NoticeListEntry />} />
        <Route path="/privacy" element={<PrivacyPage />} />
        <Route path="/terms" element={<TermsPage />} />
      </Routes>
    </BrowserRouter>,
  )
}

function currentLocation(): string {
  return window.location.pathname + window.location.search
}

beforeEach(() => {
  originalUrl = window.location.href
  originalState = window.history.state
})

afterEach(() => {
  cleanup()
  window.history.replaceState(originalState, '', originalUrl)
})

describe('법적 문서 돌아가기', () => {
  it.each(LEGAL_PAGES)(
    '$title 주소로 직접 진입하면 랜딩으로 돌아간다',
    async ({ path }) => {
      window.history.replaceState(null, '', path)
      expect(window.history.state).toBeNull()
      renderBrowserRouter()

      expect(window.history.state).toMatchObject({ idx: 0 })
      fireEvent.click(screen.getByRole('link', { name: '← 돌아가기' }))

      await waitFor(() => expect(currentLocation()).toBe('/'))
    },
  )

  it.each(LEGAL_PAGES)(
    '$title에 목록에서 진입하면 필터와 페이지를 그대로 복원한다',
    async ({ path, title }) => {
      window.history.replaceState(null, '', LIST_LOCATION)
      const expectedLocation = currentLocation()
      expect(window.history.state).toBeNull()
      renderBrowserRouter()

      expect(window.history.state).toMatchObject({ idx: 0 })
      fireEvent.click(screen.getByRole('link', { name: title }))

      await screen.findByRole('heading', { name: title, level: 1 })
      expect(currentLocation()).toBe(path)
      expect(window.history.state).toMatchObject({ idx: 1 })

      fireEvent.click(screen.getByRole('link', { name: '← 돌아가기' }))

      await waitFor(() => expect(currentLocation()).toBe(expectedLocation))
      expect(screen.getByRole('link', { name: title })).toBeInTheDocument()
    },
  )
})
