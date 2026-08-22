#!/usr/bin/env bash
#
# commitlint 규칙이 실제로 거르는지 본다 (#91).
#
# 규칙은 켜 놓고 확인하지 않으면 조용히 죽는다 — 오타 하나로 type-enum이 비어도 모든 메시지가
# 통과하고, 아무도 알아채지 못한다. 그래서 통과해야 하는 예와 막혀야 하는 예를 함께 먹인다.
#
# 커밋 메시지를 검사하는 것이 아니라 **규칙 자체를 검사한다.** 히스토리를 지키는 관문은
# `Lint PR title`이다 (CONTRIBUTING.md 3절).

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

failures=0

lint() {
  printf '%s' "$1" | npx --no -- commitlint >/dev/null 2>&1
}

expect_pass() {
  if ! lint "$1"; then
    echo "통과해야 하는 메시지가 막혔습니다: $1" >&2
    failures=$((failures + 1))
  fi
}

expect_fail() {
  if lint "$1"; then
    echo "막혀야 하는 메시지가 통과했습니다: $1" >&2
    failures=$((failures + 1))
  fi
}

# --- 통과 -------------------------------------------------------------------

# CONTRIBUTING.md 1절의 예시 그대로.
expect_pass 'feat: 공지사항 CRUD 구현'
expect_pass 'fix: 카테고리 선택 시 무한 렌더링 수정'
expect_pass 'chore: ECS 태스크 메모리 1024로 상향'

# 명사형과 서술형을 모두 허용한다. 실제 히스토리가 반반이다 (#91).
expect_pass 'docs: 활동사진 업로드 경로 확정'
expect_pass 'fix: 로그인 세션 발급과 상태 변경이 겹치는 창을 닫는다'

# 표의 9종이 모두 살아 있어야 한다. 하나라도 빠지면 그 type을 쓰는 사람이 막힌다.
for type in feat fix docs design cicd refactor test chore release; do
  expect_pass "$type: 형식 확인"
done

# 출시 PR 제목 (CONTRIBUTING.md 2절). 이 형태가 막히면 릴리스를 올릴 수 없다.
expect_pass 'release: v0.1.0 출시'

# 영문도 막지 않는다. 한글을 권할 뿐 강제하지 않는다.
expect_pass 'chore: bump dependencies'

# --- 거절 -------------------------------------------------------------------

# 표에 없는 type.
expect_fail 'wip: 대충 저장'
expect_fail 'update: 뭔가 고침'

# type이 아예 없다.
expect_fail '공지사항 CRUD 구현'
expect_fail '그냥 저장'

# 설명이 비었다.
expect_fail 'feat:'

# scope는 쓰지 않는다 (1절). `Lint PR title`의 disallowScopes와 같은 규칙이다.
expect_fail 'feat(web): 공지 화면 추가'
expect_fail 'fix(api): 세션 갱신 수정'

# 제목에 이슈 번호를 넣지 않는다 (1절). squash merge가 자동으로 붙인다.
expect_fail 'feat: 공지 조회 API 구현 #110'
expect_fail 'fix: 세션 갱신 수정 (#91)'

# 72자를 넘는다. squash merge가 " (#123)"을 덧붙이므로 여유가 필요하다.
expect_fail "docs: $(printf '가%.0s' $(seq 1 80))"

# --- 결과 -------------------------------------------------------------------

if [ "$failures" -ne 0 ]; then
  echo "commitlint 규칙 검사 실패: $failures건" >&2
  exit 1
fi

echo "commitlint 규칙 검사 통과"
