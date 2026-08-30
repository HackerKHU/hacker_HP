import { startTransition, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import type { ContentSummary } from '@/api/adminUsers'
import { myContentSummary, withdraw } from '@/api/auth'
import { ApiError } from '@/api/client'
import { useSession } from '@/auth/session'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
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
import { cn } from '@/lib/utils'

/**
 * 남을 콘텐츠를 한 문장으로. **"없음"과 "모름"을 가른다** (spec 5-TESTING T-395 MUST).
 *
 * 못 읽었는데 "0건"으로 보이면 남는 것이 없다고 읽혀, 사용자가 그 전제로 되돌릴 수 없는
 * 조작을 한다. 관리자 제거 확인 창과 같은 문장 구조다 — 같은 처리라 같게 읽혀야 한다.
 */
function describe(summary: ContentSummary | 'failed' | null): string {
  if (summary === null) return '남을 콘텐츠를 확인하는 중입니다.'
  if (summary === 'failed') {
    return '남을 콘텐츠 건수를 불러오지 못했습니다. 다시 시도해 주세요.'
  }
  return `자료 ${summary.notes}건, 공지 ${summary.notices}건, 활동사진 ${summary.photos}건, 게시글 ${summary.posts}건이 "탈퇴한 회원"으로 남습니다.`
}

function total(summary: ContentSummary): number {
  return summary.notes + summary.notices + summary.photos + summary.posts
}

/**
 * 회원 탈퇴 진입점과 확인 창 (#226, spec [2-1 §2-1-9](../../../../spec/2-1-USER-STORIES.md)).
 *
 * **진입점이 두 곳이라 컴포넌트도 하나다.** 마이페이지와 신청·대기 화면이 이것을 함께 쓴다
 * ([3-1 §3-1-6](../../../../spec/3-1-DESIGN-ARCHITECTURE.md)) — `PENDING`은 마이페이지를 볼
 * 수 없어 신청·대기 화면이 **그쪽의 유일한 탈퇴 경로**다. 복사해 두면 둘 중 하나가 반드시
 * 어긋나고, 어긋난 쪽에서 되돌릴 수 없는 조작이 안내 없이 실행된다.
 *
 * **눈에 잘 띄지 않는 자리에 둔다.** 실수로 누를 버튼이 아니다 — 글자 버튼이고, 무게는
 * 부르는 쪽이 여백으로 정한다.
 */
export function WithdrawButton({ className }: { className?: string }) {
  const { setUser, reportApiError } = useSession()
  const navigate = useNavigate()
  const alert = useLiveAlert()

  const [open, setOpen] = useState(false)
  /**
   * 남을 콘텐츠. `null`은 아직 오지 않았다, `'failed'`는 못 받았다. 셋을 가르는 이유는
   * 위 `describe()`에 있다.
   */
  const [summary, setSummary] = useState<ContentSummary | 'failed' | null>(null)
  const [working, setWorking] = useState(false)

  /**
   * 이 창을 연 조회의 세대. 닫았다 다시 열면 값이 달라진다 — 없으면 취소된 조회의 응답이
   * 새 창의 건수를 덮어, **사용자가 본 숫자와 실제가 어긋난 채로 확인 버튼이 열린다.**
   */
  const generation = useRef(0)

  function openDialog() {
    const token = generation.current + 1
    generation.current = token
    setSummary(null)
    setOpen(true)
    myContentSummary().then(
      (next) => {
        if (generation.current === token) setSummary(next)
      },
      (caught: unknown) => {
        /*
         * **오류를 버리지 않는다** (#291 검수). 여기서 삼키면 세션이 끊긴 채(`401`)로도
         * 화면은 로그인 상태로 남아, 사용자가 "다시 시도"만 반복하게 된다. 정지당한
         * 경우(`403 SUSPENDED`)도 같다 — 그때는 정지 안내로 넘어가야 한다.
         *
         * 세션을 바꾸지 않는 실패(네트워크·5xx)에서는 `reportApiError`가 아무것도 하지
         * 않으므로, 아래 `'failed'`가 그대로 화면을 담당한다.
         */
        reportApiError(caught)
        if (generation.current === token) setSummary('failed')
      },
    )
  }

  async function run() {
    setWorking(true)
    try {
      await withdraw()
      /*
       * **세션은 이미 서버에서 끝났다.** 여기서 로그아웃을 한 번 더 부르지 않는다 —
       * 지울 세션이 없어 `401`이 돌아온다.
       *
       * **랜딩으로 보낸다** (#226). 보호 화면으로 보내면 가드가 로그인으로 다시 튕겨,
       * 방금 계정을 지운 사람이 로그인 화면을 마주한다. `replace`라 **뒤로 가기로
       * 마이페이지에 돌아와도** 히스토리에 그 항목이 없다 — 남아 있어도 가드가 막지만,
       * 되돌릴 수 없는 조작 뒤에 화면이 잠깐이라도 되살아나 보이지 않는 편이 낫다.
       */
      /*
       * **둘을 한 transition에 묶는다.** 따로 부르면 세션 비우기가 먼저 커밋되어, 아직
       * `/me`(또는 `/pending`)에 있는 가드가 "비로그인"을 보고 **로그인 화면으로 튕긴다** —
       * `navigate('/')`는 transition이라 한 박자 늦게 반영되고, 늦게 온 쪽이 진다. 실제로
       * 그랬다: 탈퇴가 성공했는데 랜딩이 아니라 로그인 화면이 떴다.
       *
       * 묶으면 커밋이 한 번이고 그때 이미 랜딩이라, 보호 라우트의 가드는 그릴 기회조차 없다.
       */
      startTransition(() => {
        setUser(null)
        navigate('/', { replace: true })
      })
    } catch (caught: unknown) {
      /*
       * **세션과 화면을 그대로 둔다** (spec 5-TESTING T-402 MUST). 마지막 활성 관리자
       * (`403 FORBIDDEN`)나 지우기 직전 재활성화(`409 CONCURRENT_CHANGE`)에서는 **계정이
       * 그대로 남아 있다** — 여기서 세션을 비우고 로그인으로 보내면 사용자는 탈퇴가 끝난
       * 줄 안다.
       *
       * 코드는 세션 계층에도 넘긴다 (T-116). `FORBIDDEN`·`CONCURRENT_CHANGE`는 세션을
       * 바꾸지 않고, 그 사이에 정지당한 경우(`403 SUSPENDED`)만 정지 안내로 넘어간다.
       */
      if (!reportApiError(caught)) {
        alert.error(
          caught instanceof ApiError
            ? caught.message
            : '탈퇴하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        )
      }
    } finally {
      setWorking(false)
    }
  }

  return (
    <>
      {/*
        **실패 사유는 서버 문구를 그대로 보여준다** (#226). 마지막 활성 관리자(`403`)인지
        지우기 직전에 되살아난 것(`409`)인지는 서버가 안다 — 화면이 지어내면 둘이 같은
        말로 뭉개진다. 확인 창이 닫힌 뒤에도 남으므로 사유를 읽고 다시 누를 수 있다.
      */}
      <AlertDialog
        open={open}
        onOpenChange={(next) => {
          if (next) return
          setOpen(false)
        }}
      >
        <AlertDialogTrigger asChild>
          <Button
            type="button"
            variant="ghost"
            className={cn(
              'text-muted-foreground hover:text-foreground',
              className,
            )}
            onClick={openDialog}
          >
            회원 탈퇴
          </Button>
        </AlertDialogTrigger>

        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>정말 탈퇴할까요?</AlertDialogTitle>
            <AlertDialogDescription>{describe(summary)}</AlertDialogDescription>
          </AlertDialogHeader>

          {/*
          **되돌릴 수 없다는 것과 다시 올 수 있다는 것을 함께 적는다** (#226). 앞만 적으면
          영영 못 돌아온다고 읽혀 필요한 사람이 못 나가고, 뒤만 적으면 가벼운 조작으로 읽힌다.
        */}
          <div className="space-y-2 text-sm text-muted-foreground">
            <p>
              즐겨찾기는 함께 사라집니다. <strong>되돌릴 수 없습니다.</strong>
            </p>
            <p>같은 구글 계정으로 다시 가입할 수 있습니다.</p>
            {/*
            **지울 것이 있으면 먼저 지우고 오게 한다** (2-1 §2-1-9 — 본인은 창을 닫고 직접
            지운 뒤 다시 올 수 있다. 계정이 사라지고 나면 로그인할 수 없어 손댈 방법이 없다).
            지울 것이 없으면 이 줄도 없다 — `PENDING`이 그 경우다.
          */}
            {summary !== null && summary !== 'failed' && total(summary) > 0 && (
              <p>
                먼저 지우고 싶은 것이 있으면 취소하고{' '}
                <Link to="/notes" className="underline underline-offset-4">
                  자료게시판
                </Link>
                ·
                <Link to="/photos" className="underline underline-offset-4">
                  갤러리
                </Link>
                ·
                <Link to="/posts" className="underline underline-offset-4">
                  자유게시판
                </Link>
                에서 지운 뒤 다시 오세요.
              </p>
            )}
          </div>

          <AlertDialogFooter>
            <AlertDialogCancel disabled={working}>취소</AlertDialogCancel>
            {/*
            **무엇이 남는지 보기 전에는 누를 수 없다** (T-395 MUST). 느린 조회나 실패에서
            그냥 열어 두면 "남을 것을 확인했다"는 잘못된 전제로 되돌릴 수 없는 조작을 한다 —
            그것이 이 확인 창의 존재 이유다.

            **실패 문구는 창 밖에 그린다.** 이 버튼을 누르면 창이 닫히므로(Radix 기본 동작)
            안에 두면 사유가 창과 함께 사라져, 마지막 활성 관리자는 왜 안 되는지 못 본 채
            같은 버튼을 다시 누른다.
          */}
            <AlertDialogAction
              disabled={working || summary === null || summary === 'failed'}
              onClick={() => run()}
            >
              {working ? '탈퇴 중' : '탈퇴'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  )
}
