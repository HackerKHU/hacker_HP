import { useNavigate } from 'react-router-dom'
import { logout } from '@/api/auth'
import { ApiError } from '@/api/client'
import { useLiveAlert } from '@/components/live-alert/LiveAlertProvider'
import { useSession } from './session'

/**
 * 서버가 세션을 지웠다고 확인해 준 경우만 로그아웃 성공이다.
 *
 * 401 `UNAUTHENTICATED`도 성공으로 친다 — 지울 세션이 이미 없다는 뜻이다. 이걸 실패로 다루면
 * 만료된 세션에서는 로그아웃이 영영 안 된다. 403이 여러 코드를 공유하듯 여기서도
 * status가 아니라 `ApiError.code`로 판별한다.
 */
function isLoggedOut(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'UNAUTHENTICATED'
}

/**
 * 로그아웃 동작. **헤더가 두 개(앱·랜딩)라 이 훅 하나를 같이 쓴다.**
 * 복사해 두면 둘 중 하나가 반드시 어긋나고, 어긋난 쪽이 조용히 거짓말을 한다.
 *
 * `redirectTo`를 주면 성공 후 그 경로로 replace 이동한다. 주지 않으면 이동하지 않고
 * 세션만 비운다 — 지금 화면이 비로그인에게도 열려 있으면 굳이 옮길 이유가 없다.
 *
 * @returns 로그아웃 실행 함수. 실패 안내는 공통 live alert로 이 훅이 직접 보낸다.
 */
export function useLogout(redirectTo?: string) {
  const { setUser } = useSession()
  const navigate = useNavigate()
  const alert = useLiveAlert()

  async function run() {
    try {
      await logout()
    } catch (error: unknown) {
      if (!isLoggedOut(error)) {
        /*
         * 실패했으면 세션을 비우지도, 이동하지도 않는다.
         *
         * 서버 세션 쿠키는 HttpOnly라 브라우저에서 지울 수 없다. 화면만 로그아웃된 척하면
         * 사용자는 안전하다고 믿고 자리를 뜨는데 서버 세션은 살아 있다. 공용 PC에서
         * 다음 사람이 사이트를 열면 getMe()가 성공해 남의 계정으로 들어가진다.
         * 로그인 화면을 보여주는 것이 오히려 위험한 경우다.
         */
        alert.error('로그아웃하지 못했습니다. 다시 시도해 주세요.')
        return
      }
    }
    setUser(null)
    if (redirectTo) navigate(redirectTo, { replace: true })
  }

  return { logout: run }
}
