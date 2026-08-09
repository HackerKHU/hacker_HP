import { afterEach, describe, expect, it, vi } from 'vitest'

/**
 * 시나리오는 모듈 로드 시점에 한 번 읽힌다(`const SCENARIO = ...`). 그래서 값을 바꾸려면
 * 스텁을 먼저 걸고 모듈을 다시 불러야 한다 — `vi.resetModules()` 없이 import하면
 * 앞 테스트가 읽은 값이 그대로 남는다.
 */
async function loadFixtures(scenario: string) {
  vi.stubEnv('VITE_FIXTURE_SCENARIO', scenario)
  vi.resetModules()
  // `client`도 같은 그래프에서 가져온다. 정적 import로 받은 `ApiError`는 리셋 전
  // 모듈의 클래스라 `instanceof`가 어긋난다.
  const [fixtures, client] = await Promise.all([
    import('./fixtures'),
    import('./client'),
  ])
  return { ...fixtures, ApiError: client.ApiError }
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

  it('신청서를 내면 이어지는 조회가 제출한 값으로 신청 완료 상태를 돌려준다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('applying')

    await fixtureApplication({ studentNo: '2024001122', name: '김신입' })
    const me = await fixtureMe()

    // 이 전환이 없으면 폼을 제출해도 화면이 폼에 머문다.
    expect(me.appliedAt).not.toBeNull()
    expect(me.status).toBe('PENDING')
    // 하드코딩된 값을 돌려주면 재제출 화면에서 무엇을 고쳤는지 확인할 수 없다.
    expect(me.studentNo).toBe('2024001122')
    expect(me.name).toBe('김신입')
  })

  it('다시 제출하면 내용이 갱신된다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('applying')

    await fixtureApplication({ studentNo: '2024001122', name: '김신입' })
    await fixtureApplication({ studentNo: '2024003344', name: '김정정' })
    const me = await fixtureMe()

    expect(me.studentNo).toBe('2024003344')
    expect(me.name).toBe('김정정')
  })

  it('pending 시나리오의 재제출도 반영된다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('pending')

    await fixtureApplication({ studentNo: '2024005566', name: '박수정' })
    const me = await fixtureMe()

    expect(me.studentNo).toBe('2024005566')
    expect(me.name).toBe('박수정')
  })

  it.each([
    ['빈 문자열', '', '김신입'],
    ['공백만', '   ', '김신입'],
    ['이름이 공백만', '2024001122', '  '],
  ])(
    '%s 신청서는 거부하고 상태를 바꾸지 않는다',
    async (_, studentNo, name) => {
      const { fixtureApplication, fixtureMe, ApiError } =
        await loadFixtures('applying')

      const error = await fixtureApplication({ studentNo, name }).catch(
        (caught: unknown) => caught,
      )

      expect(error).toBeInstanceOf(ApiError)
      expect((error as InstanceType<typeof ApiError>).code).toBe(
        'VALIDATION_ERROR',
      )
      // 거부됐으면 신청 상태가 남아서는 안 된다 — 남으면 승인 대상이 된다.
      const me = await fixtureMe()
      expect(me.appliedAt).toBeNull()
    },
  )

  it.each(['user', 'admin'])(
    '%s 시나리오는 신청서 제출을 거부한다 — PENDING 전용이다',
    async (scenario) => {
      const { fixtureApplication, ApiError } = await loadFixtures(scenario)

      const error = await fixtureApplication({
        studentNo: '2024001122',
        name: '김신입',
      }).catch((caught: unknown) => caught)

      expect(error).toBeInstanceOf(ApiError)
      expect((error as InstanceType<typeof ApiError>).code).toBe('FORBIDDEN')
    },
  )

  it('pending 시나리오는 처음부터 신청 완료 상태다', async () => {
    const { fixtureMe } = await loadFixtures('pending')

    const me = await fixtureMe()

    expect(me.status).toBe('PENDING')
    expect(me.appliedAt).not.toBeNull()
  })
})
