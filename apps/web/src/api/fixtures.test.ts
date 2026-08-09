import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * 시나리오는 모듈 로드 시점에 한 번 읽힌다(`const SCENARIO = ...`). 그래서 값을 바꾸려면
 * 스텁을 먼저 걸고 모듈을 다시 불러야 한다 — `vi.resetModules()` 없이 import하면
 * 앞 테스트가 읽은 값이 그대로 남는다.
 */
async function loadFixtures(scenario: string) {
  vi.stubEnv('VITE_FIXTURE_SCENARIO', scenario)
  vi.resetModules()
  return import('./fixtures')
}

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('신청 픽스처', () => {
  it('신청 전에는 학번과 신청일이 비어 있다', async () => {
    const { fixtureMe } = await loadFixtures('applying')

    const me = await fixtureMe()

    expect(me.status).toBe('PENDING')
    expect(me.studentNo).toBeNull()
    expect(me.appliedAt).toBeNull()
  })

  it('신청서를 내면 이어지는 조회가 신청 완료 상태를 돌려준다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('applying')

    await fixtureApplication()
    const me = await fixtureMe()

    // 이 전환이 없으면 폼을 제출해도 화면이 폼에 머문다.
    expect(me.appliedAt).not.toBeNull()
    expect(me.studentNo).not.toBeNull()
    expect(me.status).toBe('PENDING')
  })

  it('pending 시나리오는 처음부터 신청 완료 상태다', async () => {
    const { fixtureMe } = await loadFixtures('pending')

    const me = await fixtureMe()

    expect(me.status).toBe('PENDING')
    expect(me.appliedAt).not.toBeNull()
  })
})
