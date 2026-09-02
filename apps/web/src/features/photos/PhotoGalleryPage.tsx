import { ThumbsUp, Trash2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { list, type Photo, remove, setPhotoLike } from '@/api/photos'
import type { Page } from '@/api/types'
import { useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import {
  KOREAN_PAGER_LABELS,
  Pager,
  parsePage,
  writePage,
} from '@/components/Pager'
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
import { PhotoLightbox } from './PhotoLightbox'

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
/**
 * 이 화면의 주소에는 `page` 말고 다른 조회 조건이 없다. 그래서 매번 새로 만든다.
 *
 * <b>컴포넌트 밖에 둔다</b> — 안에 두면 렌더마다 새 함수가 되어, 이것을 쓰는 effect가 매 렌더
 * 다시 돌거나 의존성에서 빠진 채 남는다.
 */
function pageParams(next: number): URLSearchParams {
  const params = new URLSearchParams()
  writePage(params, next)
  return params
}

export function PhotoGalleryPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { pathname } = useLocation()
  const { state, reportApiError } = useSession()
  const alert = useLiveAlert()
  const isAdmin = state.kind === 'active' && state.user.role === 'ADMIN'

  const page = parsePage(searchParams.get('page'))
  const [data, setData] = useState<Page<Photo> | null>(null)
  const [failed, setFailed] = useState(false)
  const [busy, setBusy] = useState(false)
  /**
   * 크게 보고 있는 사진. `null`이면 오버레이가 닫혀 있다 (#270).
   *
   * **사진이 아니라 id를 들고 있는다** (#351). 좋아요를 누르면 개수가 바뀌는데, 사진 자체를
   * 복사해 두면 그 복사본이 낡아 **라이트박스와 그리드의 숫자가 갈린다.** 목록이 유일한
   * 출처이고 여기서는 그중 어느 것인지만 가리킨다.
   */
  const [zoomedId, setZoomedId] = useState<number | null>(null)
  // 진행 중에 또 누르면 POST와 DELETE가 순서를 바꿔 도착해 서버 상태와 화면이 갈린다.
  const [liking, setLiking] = useState(false)
  const [reloadKey, setReloadKey] = useState(0)
  const zoomed = data?.content.find((photo) => photo.id === zoomedId) ?? null

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
      setSearchParams(pageParams(totalPages - 1), { replace: true })
    }
  }, [data, page, setSearchParams])

  function pageHref(next: number): string {
    const query = pageParams(next).toString()
    return query === '' ? pathname : `${pathname}?${query}`
  }

  /** 목록의 그 한 장만 갈아 끼운다. 그리드와 라이트박스가 같은 값을 읽는다. */
  function replacePhoto(next: Photo) {
    setData(
      (current) =>
        current && {
          ...current,
          content: current.content.map((row) =>
            row.id === next.id ? next : row,
          ),
        },
    )
  }

  /**
   * 좋아요·취소. 서버가 준 `likedByMe`를 보고 방향을 정한다 (계약 §3-2-5 — 토글이 아니다).
   *
   * **낙관적으로 먼저 반영한다** (#351 D2). 응답이 `204`라 최신 개수가 오지 않아 화면이
   * 직접 센다 — 목록을 다시 읽으면 왕복이 하나 더 늘고, 그 사이 숫자가 멈춰 있어 누른 것
   * 같지가 않다. 실패하면 누르기 전 사진으로 되돌린다.
   */
  async function toggleLike(photo: Photo) {
    const next = !photo.likedByMe
    setLiking(true)
    replacePhoto({
      ...photo,
      likedByMe: next,
      likeCount: photo.likeCount + (next ? 1 : -1),
    })
    try {
      await setPhotoLike(photo.id, next)
    } catch (caught: unknown) {
      replacePhoto(photo)
      if (!reportApiError(caught)) {
        alert.error('좋아요를 바꾸지 못했습니다. 다시 시도해 주세요.')
      }
    } finally {
      setLiking(false)
    }
  }

  /** 삭제는 되돌릴 수 없다. 확인 단계를 거친 뒤에만 여기 도달한다. */
  async function handleDelete(photo: Photo) {
    setBusy(true)
    try {
      await remove(photo.id)
      alert.success('사진을 삭제했습니다.')
      // 낙관적으로 지우지 않는다. 서버가 받아들인 뒤 다시 읽어 그린다.
      setReloadKey((key) => key + 1)
    } catch (caught: unknown) {
      if (!reportApiError(caught)) {
        alert.error(
          caught instanceof ApiError
            ? caught.message
            : '사진을 삭제하지 못했습니다. 다시 시도해 주세요.',
        )
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <PhotoLightbox
        photo={zoomed}
        onClose={() => setZoomedId(null)}
        onToggleLike={() => zoomed && toggleLike(zoomed)}
        liking={liking}
      />

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

      <div className="mt-6 min-h-[32rem]" data-list-surface="photos">
        {data === null && !failed && (
          <p className="text-sm text-muted-foreground">불러오는 중</p>
        )}

        {failed && (
          <div className="space-y-4">
            <p role="alert" className="text-sm text-muted-foreground">
              사진을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
            </p>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setReloadKey((key) => key + 1)}
            >
              다시 시도
            </Button>
          </div>
        )}

        {data !== null && data.content.length === 0 && (
          <p className="text-sm text-muted-foreground">
            등록된 사진이 없습니다.
          </p>
        )}

        {data !== null && data.content.length > 0 && (
          <>
            <p className="text-sm text-muted-foreground">
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
                   * **그 자리에서 크게 본다** (#270). 한때 원본을 새 탭으로 열었는데, 사진
                   * 한 장을 보려고 갤러리를 떠났다가 돌아와야 해 훑는 흐름이 매번 끊겼다.
                   *
                   * 링크가 아니라 버튼이다 — 다른 주소로 가지 않고 이 화면에서 열고 닫는다.
                   */}
                  <button
                    type="button"
                    onClick={() => setZoomedId(photo.id)}
                    aria-label={
                      photo.caption
                        ? `${photo.caption} 크게 보기`
                        : '사진 크게 보기'
                    }
                    className="block w-full cursor-zoom-in overflow-hidden rounded-none border border-border outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
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
                  </button>

                  <div className="mt-2 flex items-start justify-between gap-2">
                    <div className="min-w-0 flex-1 text-sm">
                      {photo.caption && (
                        <p className="truncate" title={photo.caption}>
                          {photo.caption}
                        </p>
                      )}
                      <p className="truncate text-xs text-muted-foreground">
                        {/* 업로더 이름은 절대 비지 않는다 — 제거되면 "탈퇴한 회원"이다 (§3-2-2). */}
                        {photo.uploaderName}
                        {/* 한 줄 `truncate`라 부모가 gap을 줄 수 없다. 날짜가 여백을 들고 간다. */}
                        <span className="ml-2">
                          {formatDate(photo.createdAt)}
                        </span>
                      </p>
                    </div>

                    {/*
                     * **개수만 보여준다. 버튼은 라이트박스에만 있다** (#351 D1) — 카드마다
                     * 버튼을 두면 사진을 고르려던 손이 좋아요를 남긴다.
                     *
                     * **0이어도 감추지 않는다.** 숨기면 카드마다 이 줄의 폭이 달라져
                     * 그리드가 흔들린다. 업로더·날짜와 같은 급으로 읽히게 크기를 맞춘다 —
                     * 사진이 주인공이고 숫자는 부수 정보다.
                     *
                     * 아이콘은 장식이라 감추고, 숫자만으로는 무엇의 개수인지 알 수 없으므로
                     * 읽는 기계에게 말을 준다 — 라이트박스 버튼과 같은 "좋아요 N"이다.
                     */}
                    <p className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
                      <ThumbsUp className="size-3.5" aria-hidden="true" />
                      <span className="sr-only">좋아요</span> {photo.likeCount}
                    </p>

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
                            <AlertDialogDescription asChild>
                              <div>
                                {photo.caption ? (
                                  <>
                                    <span className="block">
                                      다음 사진을 삭제합니다.
                                    </span>
                                    <span
                                      className="mt-1 block truncate font-medium text-foreground"
                                      title={photo.caption}
                                    >
                                      「{photo.caption}」
                                    </span>
                                  </>
                                ) : (
                                  <span className="block">
                                    이 사진을 삭제합니다.
                                  </span>
                                )}
                                <span className="mt-1 block">
                                  되돌릴 수 없습니다.
                                </span>
                              </div>
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
          </>
        )}
      </div>

      <Pager
        className="mt-8"
        page={page}
        totalPages={data?.page.totalPages ?? 0}
        hrefFor={pageHref}
        onGo={(next) => setSearchParams(pageParams(next))}
        labels={KOREAN_PAGER_LABELS}
      />
    </section>
  )
}
