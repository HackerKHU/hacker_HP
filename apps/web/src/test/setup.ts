import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'

/**
 * **픽스처 플래그는 테스트에서 언제나 꺼져 있다.**
 *
 * `src/api/*.ts`의 어댑터는 전부 `import.meta.env.VITE_USE_FIXTURES === 'true'`면 실제 요청
 * 대신 더미를 돌려준다. Vite는 각자의 `.env.local`을 읽으므로, 그 파일에 플래그를 켜 둔
 * 사람의 기계에서는 **method·경로·본문을 재는 테스트가 통째로 무너진다** — 실제로
 * `adminUsers.test.ts` 전부가 그렇게 실패한다. 켜 둘 이유가 있는 사람일수록(화면 개발 중)
 * 더 자주 겪는다.
 *
 * **테스트가 무엇을 재는지는 각자의 `.env.local`이 정할 일이 아니다.** 그래서 파일마다
 * 스텁을 흩지 않고 여기서 한 번 못 박는다 — 어댑터 테스트가 늘 때마다 빠뜨릴 자리가 없다.
 *
 * 픽스처 자체를 보는 테스트(`fixtures.test.ts`)는 이 플래그를 읽지 않는다. 그쪽은 픽스처
 * 함수를 직접 부르고 `VITE_FIXTURE_SCENARIO`만 스텁한다.
 */
beforeEach(() => {
  vi.stubEnv('VITE_USE_FIXTURES', 'false')
})

afterEach(() => {
  vi.unstubAllEnvs()
})

// vitest globals를 켜지 않아서 Testing Library의 자동 정리가 등록되지 않는다.
// 없으면 테스트마다 DOM이 쌓여, 같은 화면에 도달하는 테스트끼리 서로를 오염시킨다.
afterEach(cleanup)

/**
 * `findBy*`·`waitFor`의 대기 한도. 기본값 1초는 **부하가 걸린 기계에서 빠듯하다.**
 *
 * 스위트를 연달아 돌리며 재현해 보니, 기다리는 대상이 맞는데도 1초를 넘겨 실패하는
 * 경우가 40회에 두 번 있었다(1250ms·1767ms). 이건 논리 경합이 아니라 **예산 문제**다 —
 * 경합은 아무리 기다려도 안 오는 것을 기다리는 것이고, 이쪽은 오긴 오는데 늦은 것이다.
 *
 * 한도를 올리는 것으로 경합을 덮지는 못한다. 잘못된 대상을 기다리면 5초를 줘도 실패한다.
 * 그래서 이 값은 "느린 기계에서도 정상 동작이 통과하게" 하는 몫만 한다.
 */
configure({ asyncUtilTimeout: 5000 })
