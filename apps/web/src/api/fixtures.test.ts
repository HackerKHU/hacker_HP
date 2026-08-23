import { afterEach, describe, expect, it, vi } from 'vitest'
import type { User } from './types'

/**
 * 시나리오는 모듈 로드 시점에 한 번 읽힌다(`const SCENARIO = ...`). 그래서 값을 바꾸려면
 * 스텁을 먼저 걸고 모듈을 다시 불러야 한다 — `vi.resetModules()` 없이 import하면
 * 앞 테스트가 읽은 값이 그대로 남는다.
 */
/**
 * 로그인 시나리오의 세션 확인.
 *
 * `fixtureMe()`는 비로그인이면 `null`이다 (#190). 아래 사례들은 모두 로그인 시나리오라
 * `null`이 나올 수 없는데, 타입은 그것을 모른다 — 캐스트로 덮는 대신 여기서 끊는다.
 * 정말 비면 그 사실이 실패 메시지로 드러난다.
 */
async function signedIn(fixtureMe: () => Promise<User | null>): Promise<User> {
  const me = await fixtureMe()
  if (!me) throw new Error('로그인 시나리오인데 세션 확인이 비었다')
  return me
}

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

    const me = await signedIn(fixtureMe)

    expect(me.status).toBe('PENDING')
    expect(me.studentNo).toBeNull()
    expect(me.appliedAt).toBeNull()
  })

  it('신청서를 내면 이어지는 조회가 제출한 값으로 신청 완료 상태를 돌려준다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('applying')

    await fixtureApplication({
      studentNo: '2024001122',
      department: '인공지능학과',
    })
    const me = await signedIn(fixtureMe)

    // 이 전환이 없으면 폼을 제출해도 화면이 폼에 머문다.
    expect(me.appliedAt).not.toBeNull()
    expect(me.status).toBe('PENDING')
    // 하드코딩된 값을 돌려주면 재제출 화면에서 무엇을 고쳤는지 확인할 수 없다.
    expect(me.studentNo).toBe('2024001122')
    expect(me.department).toBe('인공지능학과')
  })

  it('다시 제출하면 내용이 갱신된다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('applying')

    await fixtureApplication({
      studentNo: '2024001122',
      department: '컴퓨터공학과',
    })
    await fixtureApplication({
      studentNo: '2024003344',
      department: '인공지능학과',
    })
    const me = await signedIn(fixtureMe)

    expect(me.studentNo).toBe('2024003344')
    expect(me.department).toBe('인공지능학과')
  })

  it('pending 시나리오의 재제출도 반영된다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('pending')

    await fixtureApplication({
      studentNo: '2024005566',
      department: '인공지능학과',
    })
    const me = await signedIn(fixtureMe)

    expect(me.studentNo).toBe('2024005566')
    expect(me.department).toBe('인공지능학과')
  })

  /*
   * 이름은 신청서가 바꾸지 못한다 (#224). 픽스처가 본문의 이름을 반영하면 화면 개발 중에는
   * 폼이 이름을 고칠 수 있는 것처럼 보이고, 서버가 붙는 날 그 화면이 거짓이었음이 드러난다.
   */
  it('신청서를 내도 이름은 구글 계정의 값 그대로다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('applying')

    const before = await signedIn(fixtureMe)
    await fixtureApplication({
      studentNo: '2024001122',
      department: '컴퓨터공학과',
    })
    const after = await signedIn(fixtureMe)

    expect(after.name).toBe(before.name)
  })

  it.each([
    ['빈 문자열', ''],
    ['공백만', '   '],
  ])(
    '학번이 %s인 신청서는 거부하고 상태를 바꾸지 않는다',
    async (_, studentNo) => {
      const { fixtureApplication, fixtureMe, ApiError } =
        await loadFixtures('applying')

      const error = await fixtureApplication({
        studentNo,
        department: '컴퓨터공학과',
      }).catch((caught: unknown) => caught)

      expect(error).toBeInstanceOf(ApiError)
      expect((error as InstanceType<typeof ApiError>).code).toBe(
        'VALIDATION_ERROR',
      )
      // 거부됐으면 신청 상태가 남아서는 안 된다 — 남으면 승인 대상이 된다.
      const me = await signedIn(fixtureMe)
      expect(me.appliedAt).toBeNull()
    },
  )

  it.each(['user', 'admin'])(
    '%s 시나리오는 신청서 제출을 거부한다 — PENDING 전용이다',
    async (scenario) => {
      const { fixtureApplication, ApiError } = await loadFixtures(scenario)

      const error = await fixtureApplication({
        studentNo: '2024001122',
        department: '컴퓨터공학과',
      }).catch((caught: unknown) => caught)

      expect(error).toBeInstanceOf(ApiError)
      expect((error as InstanceType<typeof ApiError>).code).toBe('FORBIDDEN')
    },
  )

  it('pending 시나리오는 처음부터 신청 완료 상태다', async () => {
    const { fixtureMe } = await loadFixtures('pending')

    const me = await signedIn(fixtureMe)

    expect(me.status).toBe('PENDING')
    expect(me.appliedAt).not.toBeNull()
  })
})

/**
 * 공지 쓰기 픽스처.
 *
 * 화면 테스트는 `@/api/notices`를 통째로 mock하므로 이 계층은 그 뒤에 가려져 있다.
 * **상태 전이와 서버 계약 재현은 여기서 본다** — 픽스처가 계약보다 무르면 오류 UI 없이도
 * 화면이 멀쩡해 보이고, 그 회귀는 서버가 붙는 날까지 드러나지 않는다.
 */
/**
 * 신청 픽스처가 서버 계약을 그대로 거부하는지.
 *
 * 화면 테스트는 `@/api/auth`를 통째로 mock하고 임의의 오류를 주입하므로 **이 계층은 그
 * 뒤에 가려져 있다.** mock 사용 자체는 맞다(검증 대상이 다르다) — 빠진 것은 여기다.
 * 픽스처가 계약보다 무르면 오류 UI 없이도 폼이 멀쩡해 보이고, 그 회귀는 서버가 붙는
 * 날까지 드러나지 않는다.
 */
describe('신청 픽스처', () => {
  /*
   * 학번 중복 거부 (§3-1-4, T-07). 통과시키면 한 학번으로 여러 계정이 만들어지는 경로를
   * 화면에서 못 잡고, 409를 보여주는 UI도 검증할 수 없다.
   */
  it('이미 쓰이고 있는 학번이면 DUPLICATE_STUDENT_NO로 거부한다', async () => {
    /*
     * 명부는 `admin`으로 읽고 신청은 `applying`으로 한다 — 신청 API는 PENDING 전용이라
     * `admin`으로 내면 학번을 보기도 전에 FORBIDDEN이다. 두 시나리오는 같은 모듈 상태를
     * 공유하지 않으므로(매번 `resetModules`) 명부 값을 옮겨 쓴다.
     */
    const admin = await loadFixtures('admin')
    const page = await admin.fixtureAdminUsers({ size: 100 })
    const taken = page.content.find((user) => user.studentNo !== null)
    if (!taken?.studentNo) throw new Error('학번을 가진 회원이 없다')

    const { fixtureApplication, ApiError } = await loadFixtures('applying')
    const error = await fixtureApplication({
      studentNo: taken.studentNo,
      department: '컴퓨터공학과',
    }).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as InstanceType<typeof ApiError>).code).toBe(
      'DUPLICATE_STUDENT_NO',
    )
  })

  it('쓰이지 않는 학번은 통과한다', async () => {
    const { fixtureApplication, fixtureMe } = await loadFixtures('applying')

    await fixtureApplication({
      studentNo: '9999999999',
      department: '컴퓨터공학과',
    })

    const me = await signedIn(fixtureMe)
    expect(me.studentNo).toBe('9999999999')
    expect(me.appliedAt).not.toBeNull()
  })

  // 공백 검증 (§3-2-3 MUST, T-52) — 빈 신청서가 승인 대상이 되면 안 된다.
  it.each([
    ['학번이 공백', ' ', '컴퓨터공학과'],
    ['학과가 목록 밖', '9999999999', '존재하지않는학과'],
  ])(
    '%s이면 VALIDATION_ERROR로 거부한다',
    async (_label, studentNo, department) => {
      const { fixtureApplication, ApiError } = await loadFixtures('applying')

      const error = await fixtureApplication({
        studentNo,
        department,
      }).catch((caught: unknown) => caught)

      expect(error).toBeInstanceOf(ApiError)
      expect((error as InstanceType<typeof ApiError>).code).toBe(
        'VALIDATION_ERROR',
      )
    },
  )

  // 신청 API는 PENDING 전용이다 (§3-1-6 MUST, T-50).
  it.each(['user', 'admin'])(
    '%s 시나리오는 FORBIDDEN으로 거부한다',
    async (scenario) => {
      const { fixtureApplication, ApiError } = await loadFixtures(scenario)

      const error = await fixtureApplication({
        studentNo: '9999999999',
        department: '컴퓨터공학과',
      }).catch((caught: unknown) => caught)

      expect(error).toBeInstanceOf(ApiError)
      expect((error as InstanceType<typeof ApiError>).code).toBe('FORBIDDEN')
    },
  )
})

describe('공지 쓰기 픽스처', () => {
  it('등록한 공지가 목록과 상세에 남는다', async () => {
    const { fixtureCreateNotice, fixtureNotice, fixtureNotices } =
      await loadFixtures('admin')

    const before = await fixtureNotices()
    const created = await fixtureCreateNotice({
      title: '새 공지',
      content: '새 본문',
    })
    const after = await fixtureNotices()

    expect(created.isPinned).toBe(false)
    expect(after.page.totalElements).toBe(before.page.totalElements + 1)
    // 저장했는데 다시 못 읽으면 목록↔작성↔상세 왕복을 화면에서 확인할 수 없다.
    await expect(fixtureNotice(created.id)).resolves.toMatchObject({
      title: '새 공지',
      content: '새 본문',
    })
  })

  it('수정은 준 필드만 바꾸고 나머지는 그대로 둔다 — PATCH다', async () => {
    const { fixtureCreateNotice, fixtureUpdateNotice, fixtureNotice } =
      await loadFixtures('admin')

    const created = await fixtureCreateNotice({
      title: '원래 제목',
      content: '원래 본문',
    })
    await fixtureUpdateNotice(created.id, { title: '고친 제목' })

    await expect(fixtureNotice(created.id)).resolves.toMatchObject({
      title: '고친 제목',
      content: '원래 본문',
    })
  })

  it('삭제한 공지는 상세에서 NOT_FOUND다', async () => {
    const {
      fixtureCreateNotice,
      fixtureRemoveNotice,
      fixtureNotice,
      ApiError,
    } = await loadFixtures('admin')

    const created = await fixtureCreateNotice({
      title: '지울 공지',
      content: '본문',
    })
    await fixtureRemoveNotice(created.id)

    const error = await fixtureNotice(created.id).catch(
      (caught: unknown) => caught,
    )
    expect(error).toBeInstanceOf(ApiError)
    expect((error as InstanceType<typeof ApiError>).code).toBe('NOT_FOUND')
  })

  it('고정하면 목록 맨 앞으로 올라간다', async () => {
    const { fixtureNotices, fixtureTogglePin } = await loadFixtures('admin')

    const before = await fixtureNotices()
    // 고정되지 않은 것 중 하나를 고른다. 이미 고정된 것을 누르면 해제가 된다.
    const target = before.content.find((notice) => !notice.isPinned)
    if (!target) throw new Error('고정되지 않은 픽스처 공지가 없다')

    await fixtureTogglePin(target.id)
    const after = await fixtureNotices()

    expect(after.content[0].id).toBe(target.id)
    expect(after.content[0].isPinned).toBe(true)
  })

  /**
   * 공지를 전부 지운 뒤 등록해도 유효한 id가 나온다.
   *
   * 회귀 — 예전에는 `Math.max(...NOTICES.map(...))`으로 발급해서 배열이 비면 `-Infinity`가
   * 나왔다. `/notices/-Infinity` 주소와 중복 key가 생긴다. 배열을 고치는 구조를 골랐으면
   * 빈 상태도 정상 상태다.
   */
  it('공지를 모두 지운 뒤 등록해도 id가 유효하고 서로 다르다', async () => {
    const { fixtureNotices, fixtureRemoveNotice, fixtureCreateNotice } =
      await loadFixtures('admin')

    const all = await fixtureNotices(0, 1000)
    for (const notice of all.content) {
      await fixtureRemoveNotice(notice.id)
    }
    expect((await fixtureNotices()).page.totalElements).toBe(0)

    const first = await fixtureCreateNotice({ title: '첫', content: '본문' })
    const second = await fixtureCreateNotice({ title: '둘', content: '본문' })

    for (const id of [first.id, second.id]) {
      expect(Number.isSafeInteger(id)).toBe(true)
      expect(id).toBeGreaterThan(0)
    }
    expect(first.id).not.toBe(second.id)
  })

  // 서버 계약 재현 — 빈 값과 200자 초과는 서버가 거부한다 (spec §3-2-2 notices).
  it.each([
    ['제목이 공백뿐', '   ', '본문'],
    ['내용이 공백뿐', '제목', '   '],
    ['제목이 200자 초과', 'ㄱ'.repeat(201), '본문'],
  ])('%s이면 VALIDATION_ERROR로 거부한다', async (_label, title, content) => {
    const { fixtureCreateNotice, ApiError } = await loadFixtures('admin')

    const error = await fixtureCreateNotice({ title, content }).catch(
      (caught: unknown) => caught,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect((error as InstanceType<typeof ApiError>).code).toBe(
      'VALIDATION_ERROR',
    )
  })

  /*
   * 쓰기 넷이 **모두** ADMIN 전용이다 (spec §3-1-3, 계약 §3-2-5).
   *
   * 회귀 — 고정 토글만 이 검사를 안 타서 `user` 시나리오에서도 성공했다. 넷을 한 표에
   * 묶어두면 다음에 쓰기가 하나 늘 때 여기에 줄을 더하지 않고는 넘어가기 어렵다.
   */
  it.each(['user', 'pending', 'blocked'])(
    '%s 시나리오는 공지 쓰기를 전부 거부한다',
    async (scenario) => {
      const fixtures = await loadFixtures(scenario)
      const { ApiError } = fixtures

      const attempts = [
        fixtures.fixtureCreateNotice({ title: '제목', content: '본문' }),
        fixtures.fixtureUpdateNotice(101, { title: '제목' }),
        fixtures.fixtureRemoveNotice(101),
        fixtures.fixtureTogglePin(101),
      ]

      for (const attempt of attempts) {
        const error = await attempt.catch((caught: unknown) => caught)
        expect(error).toBeInstanceOf(ApiError)
        expect((error as InstanceType<typeof ApiError>).code).toBe('FORBIDDEN')
      }
    },
  )

  it('guest 시나리오는 UNAUTHENTICATED로 거부한다', async () => {
    const { fixtureTogglePin, ApiError } = await loadFixtures('guest')

    const error = await fixtureTogglePin(101).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as InstanceType<typeof ApiError>).code).toBe(
      'UNAUTHENTICATED',
    )
  })
})

/**
 * 회원 관리 픽스처.
 *
 * 화면 테스트는 `@/api/adminUsers`를 통째로 mock하므로 이 계층은 그 뒤에 가려져 있다.
 * **서버 계약 재현은 여기서 본다** — 픽스처가 계약보다 무르면 오류 UI 없이도 화면이
 * 멀쩡해 보이고, 그 회귀는 서버가 붙는 날까지 드러나지 않는다.
 */
describe('회원 관리 픽스처', () => {
  it('관리자가 아니면 목록을 거부한다', async () => {
    const { fixtureAdminUsers, ApiError } = await loadFixtures('user')

    const error = await fixtureAdminUsers().catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as InstanceType<typeof ApiError>).code).toBe('FORBIDDEN')
  })

  it('명단에 신청서를 내지 않은 PENDING이 들어 있다', async () => {
    const { fixtureAdminUsers } = await loadFixtures('admin')

    const page = await fixtureAdminUsers({ status: 'PENDING', size: 100 })

    // 이런 계정이 없으면 "선택되지 않는다"는 화면 규칙을 확인할 수가 없다.
    expect(page.content.some((user) => user.appliedAt === null)).toBe(true)
    expect(page.content.some((user) => user.appliedAt !== null)).toBe(true)
  })

  /*
   * 계약 §3-2-6 MUST — 신청하지 않은 계정의 id가 섞여 오면 그 건은 실패로 집계하고
   * 상태를 바꾸지 않는다. 픽스처가 통과시키면 학번 없는 ACTIVE가 만들어지는 경로를
   * 화면에서 못 잡고, 실패 건수를 안내하는 UI도 늘 "전부 성공"이라 검증되지 않는다.
   */
  it('신청하지 않은 계정은 실패로 집계하고 상태를 바꾸지 않는다', async () => {
    const { fixtureAdminUsers, fixtureApproveUsers } =
      await loadFixtures('admin')

    const pending = await fixtureAdminUsers({ status: 'PENDING', size: 100 })
    const applied = pending.content.find((user) => user.appliedAt !== null)
    const notApplied = pending.content.find((user) => user.appliedAt === null)
    if (!applied || !notApplied) throw new Error('픽스처 명단이 부족하다')

    const result = await fixtureApproveUsers([applied.id, notApplied.id])

    expect(result.approved).toEqual([applied.id])
    expect(result.failed).toEqual([
      { userId: notApplied.id, reason: 'NOT_APPLIED' },
    ])

    const after = await fixtureAdminUsers({ size: 100 })
    const stayed = after.content.find((user) => user.id === notApplied.id)
    expect(stayed?.status).toBe('PENDING')
    const approved = after.content.find((user) => user.id === applied.id)
    expect(approved?.status).toBe('ACTIVE')

    // 방금 승인된 계정을 다시 보내면 사유가 다르다. 뭉개면 화면이 거짓 원인을 안내한다.
    const again = await fixtureApproveUsers([applied.id])
    expect(again.failed).toEqual([
      { userId: applied.id, reason: 'NOT_PENDING' },
    ])

    // 같은 id를 두 번 보내도 한 건이다. 서버가 중복을 지우므로 부분 실패가 나올 수 없다.
    const other = pending.content.find(
      (user) => user.appliedAt !== null && user.id !== applied.id,
    )
    if (!other) throw new Error('픽스처 명단이 부족하다')
    const twice = await fixtureApproveUsers([other.id, other.id])
    expect(twice.approved).toEqual([other.id])
    expect(twice.failed).toEqual([])
    expect(approved?.approvedAt).not.toBeNull()
  })

  it('검색은 이름·학번·이메일을 함께 본다', async () => {
    const { fixtureAdminUsers } = await loadFixtures('admin')

    const all = await fixtureAdminUsers({ size: 100 })
    const target = all.content.find((user) => user.studentNo !== null)
    if (!target) throw new Error('학번 있는 회원이 없다')

    for (const keyword of [target.name, target.studentNo ?? '', target.email]) {
      const found = await fixtureAdminUsers({ q: keyword, size: 100 })
      expect(
        found.content.map((user) => user.id),
        `"${keyword}"로 찾지 못했다`,
      ).toContain(target.id)
    }
  })

  it('상태·권한 필터가 실제로 걸러낸다', async () => {
    const { fixtureAdminUsers } = await loadFixtures('admin')

    const pending = await fixtureAdminUsers({ status: 'PENDING', size: 100 })
    expect(pending.content.length).toBeGreaterThan(0)
    expect(pending.content.every((user) => user.status === 'PENDING')).toBe(
      true,
    )

    const admins = await fixtureAdminUsers({ role: 'ADMIN', size: 100 })
    expect(admins.content.length).toBeGreaterThan(0)
    expect(admins.content.every((user) => user.role === 'ADMIN')).toBe(true)
  })

  /*
   * 기본 정렬은 **신청일(`appliedAt`) 최신순**이다 (2-2 §2-2-1 MUST). `createdAt`이 아니다.
   * 픽스처가 두 날짜를 벌려 두었으므로 잘못된 필드로 정렬하면 순서가 달라진다.
   */
  /*
   * **데이터가 두 정렬을 실제로 가르는지부터 지킨다.**
   *
   * `createdAt` 순서와 `appliedAt` 순서가 같은 데이터에서는 정렬이 잘못된 필드를 봐도
   * 결과가 같아, 아래 정렬 테스트가 회귀를 못 잡는다. 이 테스트가 그 전제를 고정한다.
   */
  it('createdAt 순서와 appliedAt 순서가 서로 다르다', async () => {
    const { fixtureAdminUsers } = await loadFixtures('admin')

    const page = await fixtureAdminUsers({ size: 100 })
    const applied = page.content.filter((user) => user.appliedAt !== null)

    const byCreated = [...applied]
      .sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
      .map((user) => user.id)
    const byApplied = [...applied]
      .sort((a, b) => String(b.appliedAt).localeCompare(String(a.appliedAt)))
      .map((user) => user.id)

    expect(byApplied).not.toEqual(byCreated)
  })

  it('기본 정렬은 appliedAt 최신순이고 미신청은 뒤로 간다', async () => {
    const { fixtureAdminUsers } = await loadFixtures('admin')

    const page = await fixtureAdminUsers({ size: 100 })
    const applied = page.content.filter((user) => user.appliedAt !== null)

    for (let i = 1; i < applied.length; i++) {
      expect(
        String(applied[i - 1].appliedAt) >= String(applied[i].appliedAt),
      ).toBe(true)
    }
    const firstNull = page.content.findIndex((user) => user.appliedAt === null)
    if (firstNull !== -1) {
      expect(
        page.content.slice(firstNull).every((user) => user.appliedAt === null),
      ).toBe(true)
    }
  })

  it('이름순 정렬이 동작한다', async () => {
    const { fixtureAdminUsers } = await loadFixtures('admin')

    const page = await fixtureAdminUsers({ sort: 'name', size: 100 })

    const names = page.content.map((user) => user.name)
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b, 'ko')))
  })

  it('페이지네이션이 실제로 잘라낸다', async () => {
    const { fixtureAdminUsers } = await loadFixtures('admin')

    const first = await fixtureAdminUsers({ page: 0, size: 5 })
    const second = await fixtureAdminUsers({ page: 1, size: 5 })

    expect(first.content).toHaveLength(5)
    expect(first.page.totalPages).toBeGreaterThan(1)
    // 같은 사람이 두 페이지에 겹쳐 나오면 승인이 중복된다.
    const overlap = first.content.filter((user) =>
      second.content.some((other) => other.id === user.id),
    )
    expect(overlap).toEqual([])
  })

  /*
   * 2-2 §2-2-7 MUST — 마지막 활성 관리자는 자기 자신을 정지할 수 없다. 화면은 활성
   * 관리자가 몇 명인지 모르므로 이 판단을 하지 않는다. 픽스처가 서버처럼 거부해야
   * 그 실패 화면을 만들 수 있다.
   */
  it('마지막 활성 관리자가 되면 자기 정지가 거부된다', async () => {
    const { fixtureAdminUsers, fixtureUpdateUserStatus, ApiError } =
      await loadFixtures('admin')

    const all = await fixtureAdminUsers({ role: 'ADMIN', size: 100 })
    const others = all.content.filter((user) => user.id !== 2)
    // 본인 말고 다른 활성 관리자를 전부 정지시켜 "마지막 한 명" 상태를 만든다.
    for (const other of others) {
      await fixtureUpdateUserStatus(other.id, 'SUSPENDED')
    }

    const error = await fixtureUpdateUserStatus(2, 'SUSPENDED').catch(
      (caught: unknown) => caught,
    )

    expect(error).toBeInstanceOf(ApiError)
    expect((error as InstanceType<typeof ApiError>).code).toBe('FORBIDDEN')
    const after = await fixtureAdminUsers({ size: 100 })
    expect(after.content.find((user) => user.id === 2)?.status).toBe('ACTIVE')
  })

  it('활성 관리자가 둘 이상이면 자기 정지가 허용된다', async () => {
    const { fixtureUpdateUserStatus } = await loadFixtures('admin')

    // 명단에 활성 관리자가 셋(본인 + 둘) 있으므로 그대로 시도한다.
    const updated = await fixtureUpdateUserStatus(2, 'SUSPENDED')

    expect(updated.status).toBe('SUSPENDED')
  })

  /*
   * 2-2 §2-2-3 MUST — 정지된 회원은 **이미 로그인된 세션도 다음 요청에서 차단**된다.
   *
   * 픽스처가 본인을 정지하고도 세션을 ACTIVE ADMIN으로 계속 돌려주면, 정지된 관리자가
   * 관리 화면을 계속 쓰는 상태를 화면에서 확인할 수 없다 — 계약보다 무른 픽스처다.
   */
  it('본인을 정지하면 다음 세션 조회가 정지 상태를 돌려준다', async () => {
    const { fixtureMe, fixtureUpdateUserStatus } = await loadFixtures('admin')

    const before = await signedIn(fixtureMe)
    expect(before.status).toBe('ACTIVE')

    await fixtureUpdateUserStatus(before.id, 'SUSPENDED')

    const after = await signedIn(fixtureMe)
    expect(after.id).toBe(before.id)
    expect(after.status).toBe('SUSPENDED')
  })

  it('정지된 관리자는 회원 목록도 더 볼 수 없다', async () => {
    const { fixtureMe, fixtureUpdateUserStatus, fixtureAdminUsers, ApiError } =
      await loadFixtures('admin')

    const me = await signedIn(fixtureMe)
    await fixtureUpdateUserStatus(me.id, 'SUSPENDED')

    const error = await fixtureAdminUsers().catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as InstanceType<typeof ApiError>).code).toBe('SUSPENDED')
  })
})
