import { Navigate, Route, Routes } from 'react-router-dom'
import { AppLayout } from './components/layout/AppLayout'
import { LoginPage } from './features/auth/LoginPage'
import { PendingPage } from './features/auth/PendingPage'
import { LandingPage } from './features/landing/LandingPage'
import { PrivacyPage } from './features/legal/PrivacyPage'
import { MemberListPage } from './features/members/MemberListPage'
import { BookmarkListPage } from './features/notes/BookmarkListPage'
import { NoteDetailPage } from './features/notes/NoteDetailPage'
import { NoteFormPage } from './features/notes/NoteFormPage'
import { NoteListPage } from './features/notes/NoteListPage'
import { NoticeDetailPage } from './features/notices/NoticeDetailPage'
import { NoticeFormPage } from './features/notices/NoticeFormPage'
import { NoticeListPage } from './features/notices/NoticeListPage'
import { PhotoGalleryPage } from './features/photos/PhotoGalleryPage'
import { PhotoUploadPage } from './features/photos/PhotoUploadPage'
import { GuestOnly, PendingOnly, RequireActive } from './routes/guards'

function App() {
  return (
    <Routes>
      {/*
        공개 랜딩. **가드를 붙이지 않는다** — 어느 세션 상태에서도 열려야 한다
        (spec 5-TESTING T-57~T-61). PendingOnly나 RequireActive 아래로 옮기지 말 것.
      */}
      <Route path="/" element={<LandingPage />} />
      {/* 개인정보처리방침도 공개다. 랜딩과 같은 취급이라 가드를 붙이지 않는다. */}
      <Route path="/privacy" element={<PrivacyPage />} />

      {/*
        비로그인 진입점은 /login 하나다. 가입도 같은 구글 버튼으로 하므로
        별도 /signup은 없다 (2-1 §2-1-8, 3-3 결정 13).
      */}
      <Route element={<GuestOnly />}>
        <Route path="/login" element={<LoginPage />} />
      </Route>

      {/*
        저장된 `/signup` 링크로 들어온 사람을 로그인으로 보낸다 (2-1 §2-1-8).
        아래 wildcard에 맡기면 랜딩으로 가는데, 가입하러 온 사람이 길을 다시 찾아야 한다.
      */}
      <Route path="/signup" element={<Navigate to="/login" replace />} />

      {/* 여기부터 AppLayout(헤더 + 본문)을 쓴다. /login에는 붙이지 않는다. */}
      <Route element={<PendingOnly />}>
        <Route element={<AppLayout />}>
          <Route path="/pending" element={<PendingPage />} />
        </Route>
      </Route>

      {/* 부원 화면 — ACTIVE(USER·ADMIN) */}
      <Route element={<RequireActive />}>
        <Route element={<AppLayout />}>
          <Route path="/notices" element={<NoticeListPage />} />
          <Route path="/notices/:id" element={<NoticeDetailPage />} />

          {/*
            자료. **`/admin` 아래가 아니다** — 등록·수정·삭제 모두 `ACTIVE`면 할 수 있고
            (spec §3-1-3 매트릭스), "본인 것만"은 서버가 가른다. 관리자 전용 화면이
            아니므로 부원 라우트에 둔다.

            **갈래는 경로가 아니라 쿼리(`?category=`)에 둔다.** 경로 조각으로 두면
            (`/notes/:category`) 그 패턴이 `/notes/123`(상세)까지 삼킨다.

            **정적 경로가 `:id`보다 먼저 잡힌다.** react-router는 동적 조각보다 정적
            조각에 높은 점수를 주므로 `/notes/new`가 `/notes/:id`로 새지 않는다.
          */}
          <Route path="/notes" element={<NoteListPage />} />
          <Route path="/notes/new" element={<NoteFormPage />} />
          <Route path="/notes/:id/edit" element={<NoteFormPage />} />
          <Route path="/notes/:id" element={<NoteDetailPage />} />
          <Route path="/bookmarks" element={<BookmarkListPage />} />

          {/*
            활동사진 갤러리는 `ACTIVE`면 누구나 본다 (spec §3-1-3 매트릭스).
            **업로드·삭제만 ADMIN**이라 그쪽은 아래 관리자 라우트에 둔다.
          */}
          <Route path="/photos" element={<PhotoGalleryPage />} />
        </Route>
      </Route>

      {/* 관리자 화면 — 부원 라우트와 /admin 접두사로 분리한다 */}
      <Route element={<RequireActive requiredRole="ADMIN" />}>
        <Route element={<AppLayout />}>
          {/*
            공지 작성·수정은 한 화면이 맡는다. 목록형 "공지 관리" 화면은 두지 않는다 —
            spec §2-1-8 화면 목록에도 없고, 고정 토글은 공지 목록의 관리 모드에 있다.
          */}
          <Route path="/admin/notices/new" element={<NoticeFormPage />} />
          <Route path="/admin/notices/:id/edit" element={<NoticeFormPage />} />
          <Route path="/admin/members" element={<MemberListPage />} />
          {/*
            사진 업로드는 ADMIN 전용이다 (spec §2-1-8 화면 목록). 삭제는 별도 화면 없이
            갤러리 안에서 하되, 그 버튼도 ADMIN에게만 보인다.
          */}
          <Route path="/admin/photos/new" element={<PhotoUploadPage />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

export default App
