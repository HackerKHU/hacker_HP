import { useNavigate } from 'react-router-dom'
import { FIXTURE_LOGINS, fixtureUserFor } from '@/api/fixtures'
import { useSession } from '@/auth/session'
import { Button } from '@/components/ui/button'

/**
 * 검토용 로그인 버튼. **개발 전용이고 프로덕션 번들에 들어가면 안 된다.**
 *
 * 부르는 쪽에서 `import.meta.env.VITE_USE_FIXTURES === 'true'`를 리터럴로 평가해
 * 감싸므로, 플래그가 꺼진 빌드에서는 이 모듈 전체가 떨어져 나간다 —
 * 이유는 `api/auth.ts` 상단 주석에 있다.
 *
 * #37 로그인 화면 구현이 아니다. 화면을 검토할 때 서버를 다른 시나리오로 다시 띄우지
 * 않으려는 도구이므로 스타일을 다듬지 않는다.
 */
export function DevFixtureLogin() {
  const { setUser } = useSession()
  const navigate = useNavigate()

  return (
    <section className="mt-8 rounded-lg border border-dashed border-border p-4">
      <p className="text-sm text-muted-foreground">
        픽스처 로그인 (개발 전용) — 상태를 골라 화면을 확인합니다. 로그아웃하면
        이 화면으로 돌아옵니다.
      </p>
      <div className="mt-4 flex flex-wrap gap-2">
        {FIXTURE_LOGINS.map((option) => (
          <Button
            key={option.key}
            variant="outline"
            size="sm"
            onClick={() => {
              const user = fixtureUserFor(option.key)
              setUser(user)
              // 가드가 어차피 되돌리지만, 고른 상태의 홈으로 바로 보내 확인을 빠르게 한다.
              navigate(user.status === 'ACTIVE' ? '/notices' : '/pending', {
                replace: true,
              })
            }}
          >
            {option.label}
          </Button>
        ))}
      </div>
    </section>
  )
}
