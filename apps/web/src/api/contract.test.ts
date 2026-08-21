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

/** 출처: `spec/3-2-DESIGN-CONTRACT.md` §3-2-8 "공통 페이지 응답"의 `PagedModel` 예시. */
const PAGE_ENVELOPE = ['content', 'page'] as const
const PAGE_META = ['size', 'number', 'totalElements', 'totalPages'] as const

/** 출처: 같은 절 — "공통 요청 파라미터는 `page`(0부터 시작), `size`(기본 20)다". */
const CONTRACT_DEFAULT_PAGE_SIZE = 20

/**
 * 출처: `spec/3-2-DESIGN-CONTRACT.md` §3-2-7 "공통 에러 코드" 표의 10행.
 *
 * **손으로 옮겨 적었다. `types.ts`의 `ERROR_CODES`를 가져오지 않는다** — 그것이 검사
 * 대상이기 때문이다. 대상을 기대값으로 쓰면 구현과 픽스처가 같은 방향으로 계약을 벗어날 때
 * 이 파일은 초록불이다. 실제로 그랬다: 계약에 없는 코드를 구현 목록과 픽스처에 함께
 * 넣었더니 통과했다. 옮겨 적는 수고가 독립성의 값이다.
 */
const CONTRACT_ERROR_CODES = [
  'VALIDATION_ERROR',
  'UNAUTHENTICATED',
  'PENDING_APPROVAL',
  'SUSPENDED',
  'FORBIDDEN',
  'NOT_FOUND',
  'DUPLICATE_STUDENT_NO',
  'CONCURRENT_CHANGE',
  'FILE_TOO_LARGE',
  'UNSUPPORTED_FILE_TYPE',
] as const

/**
 * 출처: `spec/3-2-DESIGN-CONTRACT.md` §3-2-8 — "`Page` 객체를 그대로 직렬화하지 않는다
 * (MUST)"가 이름을 찍어 금지한 필드들이다. 그러면 이것들이 응답에 샌다.
 *
 * 키 집합을 정확히 비교하므로 이 목록이 없어도 걸리기는 한다. 그래도 남겨두는 이유는
 * 계약이 **이름을 찍어 금지한** 항목이라, 실패 메시지가 무엇을 어긴 것인지 말해줘야 하기
 * 때문이다.
 */
const FORBIDDEN_PAGE_FIELDS = ['pageable', 'sort', 'offset'] as const

/** 출처: `spec/5-TESTING.md` §5-4 "오류 응답 규칙"의 응답 본문 형식. */
const ERROR_BODY = ['code', 'message'] as const

/**
 * 클라이언트가 본문 위에 얹는 것. **계약이 아니라 `client.ts`의 표현이다.**
 * `status`는 HTTP 상태 코드, `name`은 `Error`의 것이다.
 *
 * 계약 본문에 이 둘을 더한 것이 `ApiError`의 필드 전부여야 한다 — 그보다 많으면
 * 픽스처가 계약에 없는 무언가를 흘리고 있는 것이다.
 */
const CLIENT_ADDED_FIELDS = ['status', 'name'] as const

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

/**
 * **페이지 응답을 내는 픽스처를 스스로 찾는다.**
 *
 * 손으로 목록을 적으면 페이지 응답 픽스처가 늘 때마다 낡는다 — 실제로 그랬다.
 * `fixtureNotices`만 적혀 있는 동안 `fixtureAdminUsers`가 생겼고, 그쪽에 `pageable`을
 * 넣어도 이 파일은 초록불이었다.
 *
 * 그래서 모듈의 export를 전부 인자 없이 불러 보고, **`content` 키를 가진 응답을 주는
 * 것**을 페이지 응답으로 본다. 인자가 필요한 픽스처는 실패하므로 자연히 걸러진다.
 */
async function pageFixtures(
  fixtures: Record<string, unknown>,
): Promise<[string, Record<string, unknown>][]> {
  const found: [string, Record<string, unknown>][] = []
  for (const [name, value] of Object.entries(fixtures)) {
    if (typeof value !== 'function') continue
    try {
      const result: unknown = await value()
      if (
        result !== null &&
        typeof result === 'object' &&
        'content' in result &&
        'page' in result
      ) {
        found.push([name, result as Record<string, unknown>])
      }
    } catch {
      // 인자가 필요하거나 거부하는 픽스처다. 페이지 응답이 아니므로 넘어간다.
    }
  }
  return found
}

describe('페이지 응답 형태', () => {
  /*
   * 발견 자체가 고장나면(예: 전부 예외를 던지게 되면) 아래 검사들이 0건을 돌면서 조용히
   * 통과한다. 지금 아는 최소 개수를 못박아 그 경우를 드러낸다.
   */
  it('페이지 응답을 내는 픽스처를 둘 이상 찾는다', async () => {
    const fixtures = await loadFixtures('admin')

    const found = await pageFixtures(fixtures)

    expect(found.map(([name]) => name).sort()).toEqual(
      expect.arrayContaining(['fixtureAdminUsers', 'fixtureNotices']),
    )
  })

  it('찾은 모든 페이지 응답이 계약 형태와 정확히 같다', async () => {
    const fixtures = await loadFixtures('admin')

    const found = await pageFixtures(fixtures)

    expect(found.length).toBeGreaterThanOrEqual(2)
    for (const [name, result] of found) {
      expect(keysOf(result), `${name}의 봉투`).toEqual(expected(PAGE_ENVELOPE))
      expect(keysOf(result.page as object), `${name}의 page 메타`).toEqual(
        expected(PAGE_META),
      )
      for (const field of FORBIDDEN_PAGE_FIELDS) {
        expect(result, `${name}에 ${field}가 샜다`).not.toHaveProperty(field)
        expect(
          result.page as object,
          `${name}의 page에 ${field}가 샜다`,
        ).not.toHaveProperty(field)
      }
    }
  })

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

  /*
   * **타입만 보지 않는다. 값도 본다.**
   *
   * 형태가 맞아도 기본값이 다르면 픽스처와 서버가 갈린다 — 인자 없이 부르는 호출에서
   * 픽스처는 한 페이지에 10건, 서버는 20건을 준다. 지금 공지 화면이 size를 명시해서
   * 안 드러날 뿐이고, 명시하지 않는 화면이 하나 생기면 그때 갈린다.
   */
  it('size를 주지 않으면 계약의 기본값을 쓴다', async () => {
    const { fixtureNotices } = await loadFixtures('user')

    const result = await fixtureNotices()

    expect(result.page.size).toBe(CONTRACT_DEFAULT_PAGE_SIZE)
    expect(result.content.length).toBeLessThanOrEqual(
      CONTRACT_DEFAULT_PAGE_SIZE,
    )
  })

  it('요청한 페이지 번호가 응답에 그대로 담긴다', async () => {
    const { fixtureNotices } = await loadFixtures('user')

    expect((await fixtureNotices(0)).page.number).toBe(0)
    expect((await fixtureNotices(2)).page.number).toBe(2)
  })

  // 범위를 넘겨도 유효한 응답이다 — content만 빈다 (`NoticeListPage`의 F-2가 여기 기댄다).
  it('범위를 넘은 페이지도 빈 content를 담은 유효한 응답이다', async () => {
    const { fixtureNotices } = await loadFixtures('user')

    const result = await fixtureNotices(999)

    expect(result.content).toEqual([])
    expect(result.page.totalElements).toBeGreaterThan(0)
  })
})

describe('오류 코드 목록', () => {
  /*
   * **이것이 진짜 계약 검사다.** 위 상수는 계약 표에서 손으로 옮긴 것이고 여기서 구현과
   * 맞춰본다. 구현 목록이 계약에서 벗어나면 — 코드가 늘든 줄든 이름이 바뀌든 — 여기서
   * 잡힌다. 다른 테스트가 `ERROR_CODES`를 기대값으로 써도 되는 근거가 이 한 건이다.
   */
  it('types.ts의 ERROR_CODES가 계약 §3-2-7 표와 정확히 같다', () => {
    expect([...ERROR_CODES].sort()).toEqual([...CONTRACT_ERROR_CODES].sort())
  })
})

describe('세션 확인', () => {
  /**
   * **비로그인은 오류가 아니라 답이다** (#190).
   *
   * 화면은 랜딩을 포함해 최초 렌더마다 이것을 부른다. 오류로 만들면 비로그인 방문자마다
   * 실패 응답이 하나씩 남고, 브라우저가 콘솔에 남기는 그 줄은 앱이 지울 수 없다.
   */
  it('세션 확인은 비로그인에게 오류가 아니다', async () => {
    const fixtures = await loadFixtures('guest')

    await expect(fixtures.fixtureMe()).resolves.toBeNull()
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
    /*
     * 비로그인 세션 확인은 여기 없다. 서버가 204로 답하므로 오류가 아니다 (#190) —
     * 그 경우는 아래 `세션 확인은 비로그인에게 오류가 아니다`가 따로 본다.
     */
    ['승인 대기 차단', 'blocked', (f) => f.fixtureMe()],
    [
      '승인된 계정의 신청서 제출',
      'user',
      (f) =>
        f.fixtureApplication({
          studentNo: '2021123456',
          name: '홍길동',
          department: '컴퓨터공학과',
        }),
    ],
    [
      '빈 신청서',
      'applying',
      (f) =>
        f.fixtureApplication({
          studentNo: ' ',
          name: ' ',
          department: '컴퓨터공학과',
        }),
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

      /*
       * **키 집합을 정확히 비교한다.** 존재만 보면 `internalSql` 같은 필드가 섞여도
       * 통과한다 — 페이지 응답은 정확히 비교하면서 오류만 느슨하면 비대칭이다.
       *
       * `stack`은 계약도 클라이언트 표현도 아닌 자바스크립트 엔진의 것이라 뺀다.
       * `message`는 `Error`가 열거되지 않는 자리에 두므로 `getOwnPropertyNames`로 읽는다.
       */
      const fields = Object.getOwnPropertyNames(api)
        .filter((key) => key !== 'stack')
        .sort()
      expect(fields).toEqual(expected([...ERROR_BODY, ...CLIENT_ADDED_FIELDS]))

      // 계약 표에서 옮겨 적은 목록으로 본다 — 구현의 ERROR_CODES를 쓰지 않는다.
      expect(CONTRACT_ERROR_CODES).toContain(api.code)
      expect(typeof api.message).toBe('string')
      expect(api.message.length).toBeGreaterThan(0)
      // 스택 트레이스·SQL·내부 경로를 담지 않는다 (spec 5-TESTING §5-4).
      expect(api.message).not.toMatch(/\bat \w|SELECT |\/Users\//)
    },
  )
})

type FixtureModule = Awaited<ReturnType<typeof loadFixtures>>
