/**
 * 커밋 메시지 규칙. 원본은 CONTRIBUTING.md 1절이고 여기는 그것을 기계가 읽는 형태로 옮긴 것이다.
 *
 * **문서와 다른 말을 하면 안 된다.** 도구가 문서에 없는 것을 막으면 사람은 도구를 끄고, 문서가
 * 도구보다 엄하면 규칙이 지켜지지 않는 채로 남는다 — 실제로 "명사형" 규칙이 그랬다 (#91).
 *
 * **관문은 CI다.** 이 훅은 편의라서, 설치하지 않은 사람도 막히지 않는다. 최종 히스토리를 지키는
 * 것은 `Lint PR title` 워크플로다 — squash merge에서 남는 것은 PR 제목이기 때문이다.
 */

/** CONTRIBUTING.md 1절의 표. `Lint PR title`의 목록과 같아야 한다. */
const TYPES = [
  'feat',
  'fix',
  'docs',
  'design',
  'cicd',
  'refactor',
  'test',
  'chore',
  /*
   * 출시 PR 전용 (`release/vX.Y.Z → main`). Conventional Commits 표준에는 없지만
   * 이 저장소는 `design`·`cicd`처럼 자체 타입을 이미 쓴다.
   *
   * 출시 PR은 Merge commit이라 제목이 히스토리에 남지 않는다. 그래도 타입을 두는 것은
   * **PR 목록에서 출시를 가르기 위해서**다 — 없으면 `chore`로 흘러들어 잡일과 섞인다.
   */
  'release',
]

/**
 * 제목에 이슈 번호를 넣지 않는다 (CONTRIBUTING.md 1절).
 *
 * <p>이슈 연결은 브랜치명과 PR 본문이 담당한다. squash merge가 `(#123)`을 자동으로 붙이므로
 * 손으로 적으면 최종 히스토리에 두 번 남는다.
 */
const noIssueNumber = {
  rules: {
    'subject-no-issue-number': ({ subject }) => [
      !/#\d+/.test(subject ?? ''),
      '제목에 이슈 번호를 넣지 않는다. 연결은 브랜치명과 PR 본문이 한다 (CONTRIBUTING.md 1절)',
    ],
  },
}

export default {
  extends: ['@commitlint/config-conventional'],
  plugins: [noIssueNumber],
  rules: {
    'type-enum': [2, 'always', TYPES],

    // scope는 쓰지 않는다. 어느 앱인지는 diff가 보여준다 (1절).
    'scope-empty': [2, 'always'],

    /*
     * 한글에는 대소문자가 없어 config-conventional의 subject-case가 뜻을 갖지 못한다.
     * 켜 둔 채로 두면 영문 커밋에만 걸려 규칙이 언어마다 달라진다.
     */
    'subject-case': [0],

    /*
     * 72자. 기본값 100보다 낮춘 이유는 squash merge가 " (#123)"을 덧붙이기 때문이다 —
     * 제목이 100자면 최종 히스토리에서 그만큼 넘친다.
     */
    'header-max-length': [2, 'always', 72],

    'subject-no-issue-number': [2, 'always'],

    /*
     * 명사형("~추가")과 서술형("~한다")을 모두 허용한다. 둘을 기계로 가릴 수 없고,
     * 실제 히스토리도 반반이다 (#91). 어느 쪽이든 읽는 데 지장이 없다.
     */
  },
}
