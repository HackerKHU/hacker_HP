# 기여 가이드

> 소규모 팀 기준. 팀원이 실제로 따를 수 있는 최소한만 담았습니다. 필요해지면 그때 추가하세요.
> 커밋/브랜치/PR 규칙을 강제하는 자동화(husky, commitlint, PR 제목 린트)는 [docs/guides/claude-code-setup.md](docs/guides/claude-code-setup.md) Phase 1에서 레포 스캐폴딩과 함께 세팅됩니다. 지금은 규칙 정의만 담겨 있습니다.

- **이슈 관리**: Jira, 키 접두사 `HACK-`

---

## 1. 커밋 메시지

형식: `<type>(<scope>): [HACK-123] 한글 설명`

### type

| type | 용도 |
|---|---|
| `feat` | 새 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서만 변경 |
| `refactor` | 기능 변경 없는 코드 구조 개선 |
| `test` | 테스트 추가/수정 |
| `chore` | 빌드, 설정, 의존성 등 그 외 |
| `ci` | CI/CD 워크플로우 변경 |

### scope

`api` | `web` | `infra` | `deps`

### 예시

```
feat(api): [HACK-12] 공지사항 CRUD 구현
fix(web): [HACK-31] 카테고리 선택 시 무한 렌더링 수정
chore(infra): [HACK-8] ECS 태스크 메모리 1024로 상향
```

- subject는 명령형으로 간결하게 ("~한다"가 아니라 "~수정", "~추가").
- 본문(body)이 필요하면 빈 줄 하나 띄우고 **왜** 바꿨는지 위주로 작성합니다. 무엇을 바꿨는지는 diff가 보여줍니다.
- `[HACK-123]`은 브랜치명에서 자동으로 추출되어 커밋 메시지 앞에 삽입됩니다 (Phase 1의 `prepare-commit-msg` 훅). 직접 타이핑할 필요 없습니다.

> **Jira 상태는 자동으로 안 바뀝니다.** PR 제목/커밋에 `HACK-123`이 들어가면 Jira 티켓의 Development 패널에 연결된 PR·커밋이 보이기만 할 뿐, 상태(Done 등)는 직접 옮기거나 Jira Automation 규칙을 별도로 설정해야 합니다.

---

## 2. 브랜치 전략

```
main         배포 브랜치. 직접 push 금지, PR을 통해서만 반영.
 └─ develop        통합 브랜치.
     └─ feature/HACK-12-notice-crud
     └─ fix/HACK-31-render-loop
     └─ chore/HACK-8-ecs-memory
```

- 기능 브랜치는 `develop`에서 분기해서 `develop`으로 PR을 보냅니다.
- `develop`이 충분히 안정되면 `develop → main` PR로 배포합니다.
- 브랜치명은 소문자, 하이픈 구분: `{type}/HACK-{번호}-{요약}`.
- 오래 사는 브랜치는 만들지 않습니다. 며칠 안에 머지가 안 되면 범위를 쪼개라는 신호입니다.

---

## 3. PR 규칙

- **제목**: 커밋 메시지와 동일한 포맷(`type(scope): [HACK-123] 설명`). Squash merge를 쓰므로 PR 제목이 곧 `main`/`develop`의 커밋 메시지가 됩니다.
- **머지 방식**: Squash merge. 기능 브랜치 안의 지저분한 커밋 히스토리가 남지 않습니다.
- **리뷰**: 최소 1명 승인 후 머지.
- **CI**: `ci.yml` 통과 필수. 실패한 PR은 리뷰 요청 전에 먼저 고칩니다.
- PR을 올리면 [`.github/PULL_REQUEST_TEMPLATE.md`](.github/PULL_REQUEST_TEMPLATE.md) 내용이 자동으로 채워집니다 (관련 티켓 / 변경 내용 / 테스트 방법 / 체크리스트).

---

## 참고

- 제품/기술 문서 전체 인덱스: [docs/README.md](docs/README.md)
- 권한 관련 작업 전 필독: [docs/architecture/auth.md](docs/architecture/auth.md)
