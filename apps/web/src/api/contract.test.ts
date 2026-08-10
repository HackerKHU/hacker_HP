import { afterEach, describe, expect, it, vi } from 'vitest'
import { ERROR_CODES } from './types'

/**
 * **픽스처가 계약 형태를 지키는지.**
 *
 * 서버에는 아직 컨트롤러가 하나도 없다. 지금 화면은 없는 API를 상대로 만들어지고 있고
 * 그게 가능한 건 픽스처 덕인데, 픽스처는 사람이 손으로 쓴 것이라 계약과 어긋나도 아무도
 * 알려주지 않는다. **서버가 붙는 날 한꺼번에 드러난다.** 이 파일이 그 사이를 메운다.
 *
 * 여기서 보는 것은 **형태**다 — 내용(어떤 공지가 몇 건인지)은 다른 테스트가 본다.
 * 계약에서 그대로 베낄 수 있는 둘만 다룬다: 페이지 응답과 오류 본문.
 */

/**
 * ── 계약에서 베낀 형태. **여기가 유일한 기대값이고 아래는 전부 이것에서 파생한다.**
 *
 * 여러 곳에 손으로 적으면 계약이 바뀔 때 일부만 고쳐지고, 그때 이 파일은 낡은 계약을
 * 지키라고 우기게 된다.
 */

/** 출처: `spec/3-2-DESIGN-CONTRACT.md` §3-2-8 (319~324행)의 `PagedModel` 예시. */
const PAGE_ENVELOPE = ['content', 'page'] as const
const PAGE_META = ['size', 'number', 'totalElements', 'totalPages'] as const

/**
 * 출처: `spec/3-2-DESIGN-CONTRACT.md` §3-2-8 (328행) — `Page`를 그대로 직렬화하지 않는다
 * (MUST). 그러면 이 내부 구현 필드들이 응답에 샌다.
 *
 * 키 집합을 정확히 비교하므로 이 목록이 없어도 걸리기는 한다. 그래도 남겨두는 이유는
 * 계약이 **이름을 찍어 금지한** 항목이라, 실패 메시지가 무엇을 어긴 것인지 말해줘야 하기
 * 때문이다.
 */
const FORBIDDEN_PAGE_FIELDS = ['pageable', 'sort', 'offset'] as const

/** 출처: `spec/5-TESTING.md` §5-4 (219행)의 오류 본문 예시. */
const ERROR_BODY = ['code', 'message'] as const

function keysOf(value: object): string[] {
  return Object.keys(value).sort()
}

function expected(keys: readonly string[]): string[] {
  return [...keys].sort()
}

/**
 * 시나리오는 모듈 로드 시점에 한 번 읽힌다. `fixtures.test.ts`와 같은 규약을 쓴다 —
 * 이유는 그 파일 상단 주석에 있다.
 */
async function loadFixtures(scenario: string) {
  vi.stubEnv('VITE_FIXTURE_SCENARIO', scenario)
  vi.resetModules()
  const [fixtures, client] = await Promise.all([
    import('./fixtures'),
    import('./client'),
  ])
  return { ...fixtures, ApiError: client.ApiError }
}

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('페이지 응답 형태', () => {
  /*
   * **키가 모자라도, 남아도 실패한다.** 부분 일치로 보면 서버가 `PagedModel` 대신 `Page`를
   * 직렬화해 `pageable`·`sort`·`offset`이 섞인 응답을 줘도 통과한다 — 계약이 금지한 바로
   * 그 상태다.
   */
  it.each([
    ['첫 페이지', 0],
    ['중간 페이지', 1],
    ['범위를 넘은 페이지', 999],
  ])('%s의 키 집합이 계약과 정확히 같다', async (_label, page) => {
    const { fixtureNotices } = await loadFixtures('user')

    const result = await fixtureNotices(page)

    expect(keysOf(result)).toEqual(expected(PAGE_ENVELOPE))
    expect(keysOf(result.page)).toEqual(expected(PAGE_META))
  })

  it('내부 구현 필드가 새지 않는다', async () => {
    const { fixtureNotices } = await loadFixtures('user')

    const result = await fixtureNotices()

    for (const field of FORBIDDEN_PAGE_FIELDS) {
      expect(result, `${field}는 응답에 나오면 안 된다`).not.toHaveProperty(
        field,
      )
      expect(
        result.page,
        `page.${field}는 응답에 나오면 안 된다`,
      ).not.toHaveProperty(field)
    }
  })

  it('page 메타는 전부 숫자이고 content는 배열이다', async () => {
    const { fixtureNotices } = await loadFixtures('user')

    const result = await fixtureNotices()

    expect(Array.isArray(result.content)).toBe(true)
    for (const key of PAGE_META) {
      expect(typeof result.page[key], `page.${key}`).toBe('number')
    }
  })

  // 범위를 넘겨도 유효한 응답이다 — content만 빈다 (`NoticeListPage`의 F-2가 여기 기댄다).
  it('범위를 넘은 페이지도 빈 content를 담은 유효한 응답이다', async () => {
    const { fixtureNotices } = await loadFixtures('user')

    const result = await fixtureNotices(999)

    expect(result.content).toEqual([])
    expect(result.page.totalElements).toBeGreaterThan(0)
  })
})

describe('오류 본문 형태', () => {
  /**
   * 픽스처가 던지는 것은 HTTP 본문이 아니라 `ApiError`다. 그래서 **본문이 계약대로 왔다면
   * 클라이언트가 만들었을 값**과 같은지를 본다 — `code`가 계약의 코드 목록 안에 있고
   * `message`가 사람에게 보여줄 수 있는 문자열인지.
   *
   * `NETWORK_ERROR`·`INVALID_RESPONSE`는 서버가 보내지 않는 클라이언트 전용 코드다
   * (`types.ts`의 `ClientErrorCode`). 픽스처가 그걸 쓰면 서버가 낼 수 없는 상황을
   * 흉내내는 것이라 실패시킨다.
   */
  const failing: [string, string, (f: FixtureModule) => Promise<unknown>][] = [
    ['비로그인 조회', 'guest', (f) => f.fixtureMe()],
    ['승인 대기 차단', 'blocked', (f) => f.fixtureMe()],
    [
      '승인된 계정의 신청서 제출',
      'user',
      (f) => f.fixtureApplication({ studentNo: '2021123456', name: '홍길동' }),
    ],
    [
      '빈 신청서',
      'applying',
      (f) => f.fixtureApplication({ studentNo: ' ', name: ' ' }),
    ],
    ['없는 공지 조회', 'user', (f) => f.fixtureNotice(-1)],
    [
      '권한 없는 공지 등록',
      'user',
      (f) => f.fixtureCreateNotice({ title: '제목', content: '본문' }),
    ],
    [
      '빈 값으로 공지 등록',
      'admin',
      (f) => f.fixtureCreateNotice({ title: ' ', content: ' ' }),
    ],
    ['없는 공지 수정', 'admin', (f) => f.fixtureUpdateNotice(-1, {})],
    ['없는 공지 삭제', 'admin', (f) => f.fixtureRemoveNotice(-1)],
    ['권한 없는 고정 토글', 'user', (f) => f.fixtureTogglePin(101)],
  ]

  it.each(failing)(
    '%s은 계약 형태의 오류를 던진다',
    async (_l, scenario, run) => {
      const fixtures = await loadFixtures(scenario)

      const error = await run(fixtures).then(
        () => null,
        (caught: unknown) => caught,
      )

      expect(error, '성공해버렸다 — 오류를 기대한 경로다').not.toBeNull()
      expect(error).toBeInstanceOf(fixtures.ApiError)

      const api = error as InstanceType<typeof fixtures.ApiError>
      // 계약 본문의 두 필드가 그대로 살아 있어야 한다.
      for (const field of ERROR_BODY) {
        expect(api, `${field}가 비어 있다`).toHaveProperty(field)
      }
      // 임의 문자열이 code에 들어가면 여기서 걸린다.
      expect(ERROR_CODES).toContain(api.code)
      expect(typeof api.message).toBe('string')
      expect(api.message.length).toBeGreaterThan(0)
      // 스택 트레이스·SQL·내부 경로를 담지 않는다 (spec 5-TESTING §5-4).
      expect(api.message).not.toMatch(/\bat \w|SELECT |\/Users\//)
    },
  )
})

type FixtureModule = Awaited<ReturnType<typeof loadFixtures>>
