# 기여 가이드

> 소규모 팀 기준. 팀원이 실제로 따를 수 있는 최소한만 담았습니다. 필요해지면 그때 추가하세요.

- **이슈 관리**: Jira 도입 예정 (키 접두사 `HACK-`). **아직 프로젝트를 만들지 않았습니다** — 티켓 키가 들어가는 규칙은 도입 시점부터 적용합니다.

---

## 1. 커밋 메시지

형식: `type: 한글 설명`

```
feat: 공지사항 CRUD 구현
fix: 카테고리 선택 시 무한 렌더링 수정
chore: ECS 태스크 메모리 1024로 상향
```

| type | 용도 |
|---|---|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 |
| `design` | UI·스타일·디자인 수정 (동작은 그대로, 보이는 결과만 바뀜) |
| `cicd` | 배포, CI/CD 설정 변경 |
| `refactor` | 기능·결과 변경 없는 코드 구조 개선 |
| `test` | 테스트 추가/수정. 프로덕션 코드 변경은 포함하지 않는다 |
| `chore` | 설정, 의존성, 그 외 유지보수 |

**`design` vs `refactor` vs `feat`/`fix` 경계** — "리뷰어가 스크린샷 없이 diff만 보고 뭐가 바뀌었는지 알 수 있는가"로 가른다.

- 시각적 결과가 바뀐다 (색상, 레이아웃, 애니메이션, 컴포넌트 분할이라도 화면이 달라 보임) → `design`
- 시각적 결과는 그대로, 코드 구조만 바뀐다 (컴포넌트 쪼개기, 훅 추출) → `refactor`
- 동작 자체가 바뀐다 (새 기능, 버그 수정이 목적이고 그 과정에서 UI가 따라 바뀜) → `feat`/`fix`

- scope는 쓰지 않습니다. 어느 앱인지는 diff가 보여줍니다.
- subject는 명령형으로 간결하게 ("~한다"가 아니라 "~수정", "~추가").
- 본문이 필요하면 빈 줄 하나 띄우고 **왜** 바꿨는지 위주로 씁니다. 무엇을 바꿨는지는 diff가 보여줍니다.
- 커밋 제목에 Jira 키를 넣지 않습니다. 티켓 연결은 브랜치명과 PR 본문이 담당합니다.

---

## 2. 브랜치 전략

```
main         배포 브랜치. 직접 push 금지, PR로만 반영
 └─ develop        통합 브랜치
     └─ feat/HACK-12-notice-crud
     └─ fix/HACK-31-render-loop
     └─ chore/HACK-8-ecs-memory
```

형식: `{type}/{JIRA_KEY}-{title-slug}`

> **Jira 도입 전까지는 키를 생략하고 `{type}/{title-slug}`를 씁니다** (예: `chore/repo-baseline`). 프로젝트를 만들면 그때부터 키를 넣습니다.

- 기능 브랜치는 `develop`에서 분기해 `develop`으로 PR을 보냅니다.
- `develop`이 충분히 안정되면 `develop → main` PR로 배포합니다.
- 분기 전 `git fetch origin` 후 **최신 `origin/develop` 기준**으로 자릅니다. 로컬 `develop`만 보고 판단하지 않습니다.
- **Jira 키는 `HACK-12`처럼 대문자로 유지**합니다. 나머지(type, slug)는 전부 소문자입니다.
- slug는 kebab-case, 영어를 우선합니다. Jira 요약이 한국어면 짧은 영어로 옮깁니다.
- type은 커밋 메시지 표와 같은 목록을 씁니다.
- 오래 사는 브랜치는 만들지 않습니다. 며칠 안에 머지가 안 되면 범위를 쪼개라는 신호입니다.

---

## 3. PR 규칙

- **제목**: 커밋 메시지와 같은 형식(`type: 한글 설명`). `Lint PR title` 워크플로가 검사합니다.
- **머지 방식**:

  | | 방식 | 이유 |
  |---|---|---|
  | 기능 브랜치 → `develop` | **Squash merge** | 브랜치 안의 지저분한 커밋이 남지 않습니다 |
  | `develop` → `main` | **Merge commit** | 어느 기능 묶음이 언제 배포됐는지 히스토리에 남습니다 |

  ruleset으로 강제되어 있어 다른 방식은 버튼이 뜨지 않습니다.

- **리뷰**: 최소 1명 승인 후 머지.
- **Jira 티켓 표기** *(도입 후 적용)*: PR 본문에 티켓 키가 나오면 **예외 없이 전부 링크로** 씁니다. 범위 표기(`HACK-12~15`)도 개별 링크로 펼칩니다.

  ```
  [HACK-12](https://<사이트>.atlassian.net/browse/HACK-12)
  ```

- PR을 올리면 [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) 내용이 자동으로 채워집니다.

> **Jira 상태는 자동으로 안 바뀝니다.** 브랜치명이나 PR 본문에 `HACK-12`가 들어가면 Jira 티켓의 Development 패널에 PR·커밋이 보이기만 할 뿐, 상태(Done 등)는 직접 옮기거나 Jira Automation 규칙을 따로 설정해야 합니다.

---

## 참고

- 제품·설계 스펙: [spec/README.md](spec/README.md)
- 권한 관련 작업 전 필독: [spec/3-1-DESIGN-ARCHITECTURE.md](spec/3-1-DESIGN-ARCHITECTURE.md)
- 인프라·배포 절차: [docs/README.md](docs/README.md)
