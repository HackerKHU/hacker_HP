import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { DEPARTMENTS } from './departments'

/*
 * 학과 목록이 세 곳에 있다 — 이 파일, `apps/api`의 `Department.ALL`, DB의 `users_department_check`
 * CHECK 제약(마이그레이션으로 정의). 한쪽만 고치면 **가입이 막힌다.**
 *
 *   웹에만 추가  → 서버가 400 VALIDATION_ERROR
 *   서버에만 추가 → 목록에 안 보여서 고를 수 없음
 *   CHECK 누락   → 신청은 통과하는데 저장이 터져 500
 *
 * 마이그레이션 주석이 "반드시 같아야 한다"고 적어뒀지만 확인하는 장치가 없었다. 사람이
 * 기억해서 맞추는 규약은 학과 개편 한 번이면 깨진다. 여기서 원본을 직접 읽어 대조한다.
 *
 * `GET /api/v1/departments`가 생기면(#166) 이 파일과 `departments.ts`를 같이 지운다.
 */

/**
 * 저장소 루트 기준으로 찾는다 — 테스트를 어디서 실행하든 같은 파일을 본다.
 *
 * 실행 위치에서 위로 올라가며 `apps/api`를 가진 디렉터리를 찾는다. `../..`로 고정하면
 * 실행 위치가 바뀌는 순간 조용히 엉뚱한 곳을 읽는다.
 */
function fromRepoRoot(relative: string): string {
  let dir = process.cwd()
  while (!existsSync(join(dir, 'apps', 'api'))) {
    const parent = dirname(dir)
    if (parent === dir) throw new Error('저장소 루트를 찾지 못했다')
    dir = parent
  }
  return resolve(dir, relative)
}

/** 자바 `List.of(...)`와 SQL `IN (...)` 안의 문자열 리터럴을 순서대로 뽑는다. */
function literals(source: string, open: string, quote: string): string[] {
  const body = source.slice(
    source.indexOf(open) + open.length,
    source.lastIndexOf(')'),
  )
  return [
    ...body.matchAll(new RegExp(`${quote}([^${quote}]+)${quote}`, 'g')),
  ].map((match) => match[1])
}

const JAVA = literals(
  readFileSync(
    fromRepoRoot(
      'apps/api/src/main/java/org/hackerkhu/hackerhp/domain/user/entity/Department.java',
    ),
    'utf8',
  ),
  'List.of(',
  '"',
)

const SQL = literals(
  readFileSync(
    fromRepoRoot(
      // CHECK 제약을 다시 정의한 가장 최근 마이그레이션이다. V3가 원본이었지만 #166에서
      // 학과명을 바로잡으며 V10이 DROP·ADD로 값을 교체했다 (V9는 develop에 먼저 병합된
      // 다른 작업이 씀) — 마이그레이션은 지난 뒤 고치지 않으므로, 그다음에도 이 제약이 또
      // 바뀌면 최신 마이그레이션 파일명으로 옮겨야 한다.
      'apps/api/src/main/resources/db/migration/V10__fix_department_names.sql',
    ),
    'utf8',
  ),
  'department IN (',
  "'",
)

describe('학과 목록 동기화', () => {
  it('서버의 Department.ALL과 값도 순서도 같다', () => {
    expect(JAVA.length).toBeGreaterThan(0)
    expect([...DEPARTMENTS]).toEqual(JAVA)
  })

  /*
   * 순서까지 보는 이유는 화면 때문이다. 소프트웨어융합대학 셋을 맨 앞에 둔 것이 의도인데,
   * 서버에서 그 순서가 바뀌면 화면도 따라가야 한다.
   */
  it('마이그레이션 CHECK 제약과도 같다', () => {
    expect(SQL.length).toBeGreaterThan(0)
    expect([...DEPARTMENTS]).toEqual(SQL)
  })

  it('중복이 없다', () => {
    expect(new Set(DEPARTMENTS).size).toBe(DEPARTMENTS.length)
  })

  /** `department varchar(50)` (spec §3-2-2). 넘치면 저장이 터진다. */
  it('컬럼 길이를 넘지 않는다', () => {
    for (const name of DEPARTMENTS) expect(name.length).toBeLessThanOrEqual(50)
  })
})
