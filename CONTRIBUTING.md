# 기여 가이드

> 소규모 팀 기준. 팀원이 실제로 따를 수 있는 최소한만 담았습니다. 필요해지면 그때 추가하세요.

- **이슈 관리**: GitHub Issues가 백로그의 단일 원본이다. 별도 이슈 관리 도구와 이중으로 기록하지 않는다.
- **마일스톤**: 1차 출시 작업은 `MVP`, 이후 기능은 `Post Launch`로 구분한다.
- **WIP 제한**: 한 사람이 동시에 `In Progress`로 진행하는 이슈는 최대 2개다.

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
- 커밋 제목에 이슈 번호를 넣지 않습니다. 이슈 연결은 브랜치명과 PR 본문이 담당합니다.

---

## 2. 브랜치 전략

```text
feat/12-notice-crud ──PR──> develop
                                  └── release/v0.1.0 ──PR──> main
fix/31-render-loop  ──PR──> develop
                       release/v0.1.0 <──PR── fix/42-release-blocker
```

- 일반 작업 브랜치: `{type}/{issue-number}-{title-slug}`
- 출시 브랜치: `release/v{major}.{minor}.{patch}` (예: `release/v0.1.0`)

이슈가 없는 저장소 설정이나 긴급 수정은 예외적으로 `{type}/{title-slug}`를 쓸 수 있다. 작업 추적이 필요한 변경은 먼저 이슈를 만든다.

- 기능 브랜치는 `develop`에서 분기해 `develop`으로 PR을 보냅니다.
- 출시 준비가 끝난 최신 `origin/develop`에서 `release/vX.Y.Z` 브랜치를 만들고, 이 브랜치에서 출시 후보를 검증합니다.
- `main`에는 `release/vX.Y.Z` 브랜치만 PR을 보냅니다. `develop → main` 직접 PR은 만들지 않습니다.
- release 브랜치를 만든 뒤에는 새 기능을 넣지 않고 출시를 막는 수정만 PR로 반영합니다.
- 출시 차단 수정 브랜치는 대상 `release/vX.Y.Z`에서 분기해 같은 release 브랜치로 PR을 보냅니다. `develop`에 추가된 다음 기능을 출시 후보에 섞지 않습니다.
- release 브랜치에만 반영된 수정이 있으면 출시 후 `release/vX.Y.Z → develop` 동기화 PR을 만듭니다. 동기화가 끝나면 ruleset 우회 권한을 가진 Organization Owner 또는 저장소 관리자가 release 브랜치를 삭제합니다.
- 분기 전 `git fetch origin`을 실행하고 대상 원격 브랜치(`origin/develop` 또는 `origin/release/vX.Y.Z`)에서 자릅니다. 로컬 브랜치만 보고 판단하지 않습니다.
- 이슈 번호는 `#` 없이 숫자만 적습니다.
- slug는 kebab-case, 영어를 우선합니다. 이슈 제목이 한국어면 짧은 영어로 옮깁니다.
- type은 커밋 메시지 표와 같은 목록을 씁니다.
- 오래 사는 브랜치는 만들지 않습니다. 며칠 안에 머지가 안 되면 범위를 쪼개라는 신호입니다.

---

## 3. PR 규칙

- **제목·출발 브랜치**: `Lint PR title` 워크플로가 제목 형식(`type: 한글 설명`)을 검사하고, `main` 대상 PR은 `release/vX.Y.Z`에서만 출발했는지 검증합니다.
- **머지 방식**:

  | | 방식 | 이유 |
  |---|---|---|
  | 기능 브랜치 → `develop` | **Squash merge** | 브랜치 안의 지저분한 커밋이 남지 않습니다 |
  | 수정 브랜치 → `release/vX.Y.Z` | **Squash merge** | 출시 후보의 변경 이력을 작게 유지합니다 |
  | `release/vX.Y.Z` → `main` | **Merge commit** | 어느 버전이 언제 배포됐는지 히스토리에 남습니다 |
  | `release/vX.Y.Z` → `develop` | **Squash merge** | release에서 발생한 수정만 통합 브랜치에 되돌립니다 |

  ruleset으로 강제되어 있어 다른 방식은 버튼이 뜨지 않습니다.

- **리뷰**: Codex 리뷰 결과를 확인하고 필요한 피드백을 반영한 뒤 머지한다.
- **사람 리뷰**: 필요할 때 요청하며 release 및 main PR을 포함해 필수 조건은 아니다.
- **이슈 연결**: PR 본문에 `Closes #12`를 적는다. 여러 이슈를 닫을 때는 번호별로 각각 적는다.
- **API 변경**: 구현 또는 변경된 API의 Swagger/OpenAPI 명세를 같은 PR에서 갱신한다.
- **화면 변경**: 확인 가능한 스크린샷 또는 짧은 영상을 PR에 첨부한다.

- PR을 올리면 [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) 내용이 자동으로 채워집니다.

---

## 4. GitHub Issues

- **제목**: 커밋·PR과 같은 `type: 한글 설명` 형식을 쓴다. type은 1절 표와 같은 목록이며, 이슈의 결과물이 무엇인지로 고른다 (문서만 바뀌면 `docs`, 화면이 생기면 `feat`). 이슈 템플릿이 접두사를 채워주므로 작업 성격에 맞게 바꿔 쓴다. 템플릿의 제목 기본값과 YAML 유효성은 `Lint issue templates` 워크플로가 검사한다.
- 구현 전에 이슈를 만들고 담당자 한 명, 마일스톤, 완료 조건을 지정한다.
- `Backlog → Ready → In Progress → Review → Done` 순서로 관리한다.
- 기능 PR이 `develop` 머지 시 이슈를 자동으로 닫게 하려면 GitHub 저장소의 default branch를 `develop`으로 설정한다. default branch가 `main`인 동안에는 PR 머지 후 이슈를 직접 닫는다.
- 막힌 작업은 `blocked` 라벨을 붙이고 원인과 필요한 도움을 댓글로 남긴다.
- 범위가 커지면 한 이슈에서 계속 늘리지 말고 후속 이슈로 분리한다.
- 세부 명세는 구현하면서 바꿀 수 있지만, 권한·스키마·API가 바뀌면 관련 `spec/` 문서도 같은 PR에서 갱신한다.
- 이슈의 완료는 코드 작성만 뜻하지 않는다. 테스트, 문서, 통합 확인과 PR 머지까지 끝나야 한다.

권장 라벨은 `backend`, `frontend`, `infra`, `docs`, `bug`, `blocked`, `launch-critical`, `post-launch`다.

---

## 참고

- 제품·설계 스펙: [spec/README.md](spec/README.md)
- 권한 관련 작업 전 필독: [spec/3-1-DESIGN-ARCHITECTURE.md](spec/3-1-DESIGN-ARCHITECTURE.md)
- 인프라·배포 절차: [docs/README.md](docs/README.md)
