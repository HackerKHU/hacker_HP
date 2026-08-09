import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// vitest globals를 켜지 않아서 Testing Library의 자동 정리가 등록되지 않는다.
// 없으면 테스트마다 DOM이 쌓여, 같은 화면에 도달하는 테스트끼리 서로를 오염시킨다.
afterEach(cleanup)
