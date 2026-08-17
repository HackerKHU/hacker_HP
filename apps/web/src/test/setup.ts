import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

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
