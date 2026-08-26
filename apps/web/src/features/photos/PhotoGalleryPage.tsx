import { Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { list, type Photo, remove } from '@/api/photos'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { Pager, parsePage } from '@/components/Pager'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'

/** 한 페이지 장수. 4열 그리드에서 5줄이 된다. */
const PAGE_SIZE = 20

/** 서버는 UTC로 내려준다. 갤러리에서는 날짜까지만 보여준다. */
function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('ko-KR', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  })
}

/**
 * 활동사진 갤러리 (spec §2-1-7).
 *
 * **최신순 그리드이고 정렬 선택지가 없다.** 서버가 `created_at DESC`로 고정해 내려주므로
 * (spec §2-1-7) 화면이 정렬을 고르지도, 다시 정렬하지도 않는다 — 클라이언트 정렬을 두면
 * 서버 순서를 덮어쓰는 코드가 하나 더 생긴다.
 *
 * **앨범 그룹은 없다** — 각 이미지가 개별 레코드다.
 *
 * 조회는 `ACTIVE`면 누구나, **삭제는 `ADMIN`만** 보인다 (spec §3-1-3 매트릭스). 노출
 * 제어일 뿐 권한 통제가 아니다 (§3-1-7) — 서버가 같은 조건으로 다시 막는다.
 */
export function PhotoGalleryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const { state, reportApiError } = useSession()
  const isAdmin = state.kind === 'active' && state.user.role === 'ADMIN'

  const page = parsePage(searchParams.get('page'))
  const [data, setData] = useState<Page<Photo> | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  // biome-ignore lint/correctness/useExhaustiveDependencies: reloadKey는 본문에서 읽지 않고 재조회 트리거로만 쓴다.
  useEffect(() => {
    let alive = true
    setData(null)
    setFailed(false)
    list({ page, size: PAGE_SIZE })
      .then((result) => {
        if (alive) setData(result)
      })
      .catch((caught: unknown) => {
        if (!alive) return
        // #36 계약 — 403 PENDING_APPROVAL이면 가드가 대기 화면으로 되돌린다.
        reportApiError(caught)
        setFailed(true)
      })
    return () => {
      alive = false
    }
  }, [page, reloadKey, reportApiError])

  /**
   * 마지막 페이지의 마지막 사진을 지우면 그 페이지가 사라진다. 되돌리지 않으면 사진이
   * 남아 있는데도 "등록된 사진이 없습니다"가 뜬다.
   */
  useEffect(() => {
    if (!data) return
    const { totalPages } = data.page
    if (totalPages >= 1 && page >= totalPages) {
      setSearchParams({ page: String(totalPages - 1) }, { replace: true })
    }
  }, [data, page, setSearchParams])

  function pageParams(next: number): Record<string, string> {
    return next === 0 ? {} : { page: String(next) }
  }

  function pageHref(next: number): string {
    const query = new URLSearchParams(pageParams(next)).toString()
    return query === '' ? pathname : `${pathname}?${query}`
  }

  /** 삭제는 되돌릴 수 없다. 확인 단계를 거친 뒤에만 여기 도달한다. */
  async function handleDelete(photo: Photo) {
    setBusy(true)
    setNotice(null)
    try {
      await remove(photo.id)
      // 낙관적으로 지우지 않는다. 서버가 받아들인 뒤 다시 읽어 그린다.
      setReloadKey((key) => key + 1)
    } catch (caught: unknown) {
      reportApiError(caught)
      setNotice(
        caught instanceof ApiError
          ? caught.message
          : '사진을 삭제하지 못했습니다. 다시 시도해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="flex items-center justify-between gap-4">
        {/*
         * 화면 이름은 **갤러리**다 (2026-08-23). 담고 있는 것은 여전히 활동사진이고
         * (spec §2-1-7) 라우트도 `/photos`지만, 사용자에게 보이는 이름은 이 하나로 통일한다.
         */}
        <h1 className="text-2xl font-semibold tracking-tight">갤러리</h1>
        {/*
         * **업로드는 `ADMIN` 전용이다** (spec §3-1-3 매트릭스). 자료와 다른 점이다 —
         * 자료는 부원 누구나 올린다. 진입점도 `/admin` 아래에 둔다.
         */}
        {isAdmin && (
          <Button variant="outline" size="sm" asChild>
            <Link to="/admin/photos/new">업로드</Link>
          </Button>
        )}
      </div>

      {notice && (
        <p role="alert" className="mt-6 text-sm text-muted-foreground">
          {notice}
        </p>
      )}

      {data === null && !failed && (
        <p className="mt-8 text-sm text-muted-foreground">불러오는 중</p>
      )}

      {failed && (
        <p role="alert" className="mt-8 text-sm text-muted-foreground">
          사진을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
        </p>
      )}

      {data !== null && data.content.length === 0 && (
        <p className="mt-8 text-sm text-muted-foreground">
          등록된 사진이 없습니다.
        </p>
      )}

      {data !== null && data.content.length > 0 && (
        <>
          <p className="mt-6 text-sm text-muted-foreground">
            전체 {data.page.totalElements}장
          </p>

          {/*
           * 그리드. 폭에 따라 열이 준다 — 사진은 표와 달리 좁은 화면에서도 볼 만하다.
           *
           * **`aspect-square`로 칸을 고정하고 `object-cover`로 채운다.** 원본 비율이
           * 제각각이라 그대로 두면 줄 높이가 들쭉날쭉해 그리드가 계단처럼 보인다.
           */}
          <ul className="mt-4 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {data.content.map((photo) => (
              <li key={photo.id} className="group relative">
                {/*
                 * **원본을 새 탭으로 연다.** 목록에는 썸네일을 쓰고(§3-2-5), 크게 보려면
                 * 원본이 필요하다. 라이트박스는 이 이슈의 범위 밖이라 브라우저에 맡긴다.
                 */}
                <a
                  href={photo.url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="block overflow-hidden rounded-none border border-border outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
                >
                  <img
                    src={photo.thumbnailUrl}
                    /*
                     * **설명이 없으면 `alt`를 비운다.** 없는 설명을 "활동사진"처럼 지어내
                     * 채우면 스크린리더가 사진마다 같은 말을 스무 번 읽는다 — 장식으로
                     * 두는 편이 낫다. 아래 설명 문단이 진짜 정보를 담는다.
                     */
                    alt={photo.caption ?? ''}
                    loading="lazy"
                    className="aspect-square w-full bg-muted object-cover transition-transform group-hover:scale-105"
                  />
                </a>

                <div className="mt-2 flex items-start justify-between gap-2">
                  <div className="min-w-0 text-sm">
                    {photo.caption && (
                      <p className="truncate">{photo.caption}</p>
                    )}
                    <p className="truncate text-xs text-muted-foreground">
                      {/* 업로더 이름은 절대 비지 않는다 — 제거되면 "탈퇴한 회원"이다 (§3-2-2). */}
                      {photo.uploaderName} · {formatDate(photo.createdAt)}
                    </p>
                  </div>

                  {isAdmin && (
                    <AlertDialog>
                      <AlertDialogTrigger asChild>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="size-7 shrink-0"
                          disabled={busy}
                          aria-label={
                            photo.caption
                              ? `${photo.caption} 삭제`
                              : `${formatDate(photo.createdAt)}에 올린 사진 삭제`
                          }
                        >
                          <Trash2 className="size-4" aria-hidden="true" />
                        </Button>
                      </AlertDialogTrigger>
                      <AlertDialogContent>
                        <AlertDialogHeader>
                          <AlertDialogTitle>
                            사진을 삭제할까요?
                          </AlertDialogTitle>
                          <AlertDialogDescription>
                            {photo.caption
                              ? `「${photo.caption}」을(를) 삭제합니다.`
                              : '이 사진을 삭제합니다.'}{' '}
                            되돌릴 수 없습니다.
                          </AlertDialogDescription>
                        </AlertDialogHeader>
                        <AlertDialogFooter>
                          <AlertDialogCancel>취소</AlertDialogCancel>
                          <AlertDialogAction
                            onClick={() => handleDelete(photo)}
                          >
                            삭제
                          </AlertDialogAction>
                        </AlertDialogFooter>
                      </AlertDialogContent>
                    </AlertDialog>
                  )}
                </div>
              </li>
            ))}
          </ul>

          <Pager
            className="mt-8"
            page={page}
            totalPages={data.page.totalPages}
            hrefFor={pageHref}
            onGo={(next) => setSearchParams(pageParams(next))}
          />
        </>
      )}
    </section>
  )
}
