import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { PAGE_CONTAINER } from './page-container'

/**
 * **정렬선 값이 이 파일 밖에 다시 생기지 않는가** (#389).
 *
 * 헤더·본문·푸터·랜딩이 같은 폭을 각자 적고 있었고, 그 어긋남이 세 번 났다
 * (#247·#258·#264). 한 벌로 모았으니 이제 다시 갈라지는 것만 막으면 된다.
 *
 * **렌더 결과로는 잡을 수 없다.** 새 화면이 자기 몫으로 폭을 적어도 그 화면은 멀쩡히
 * 그려진다 — 어긋남이 드러나는 것은 나중에 폭을 바꿀 때이고, 그때는 어디가 남았는지
 * 알 수 없다. `index.css`를 직접 읽는 `LiveAlertProvider.test.tsx`와 같은 종류다.
 */

const SRC = resolve(process.cwd(), 'src')
const HOME = resolve(SRC, 'components/page-container.ts')

/**
 * 폭 클래스를 상수에서 뽑는다. **여기에 값을 적으면 이 파일이 두 번째 사본이 된다** —
 * 그러면 자기 자신을 걸러내야 하고, 값을 바꿀 때 고칠 곳이 다시 둘이 된다.
 */
const WIDTH = PAGE_CONTAINER.split(' ').find((name) =>
  name.startsWith('max-w-'),
)

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return sourceFiles(path)
    return /\.tsx?$/.test(entry.name) ? [path] : []
  })
}

describe('화면 정렬선', () => {
  it('폭을 정하는 곳이 하나뿐이다', () => {
    expect(WIDTH).toBeDefined()

    const strays = sourceFiles(SRC)
      .filter((path) => path !== HOME)
      .filter((path) => readFileSync(path, 'utf-8').includes(String(WIDTH)))
      .map((path) => relative(SRC, path))

    expect(strays).toEqual([])
  })
})
