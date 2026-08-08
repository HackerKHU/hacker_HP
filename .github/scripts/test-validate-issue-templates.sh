#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
validator="$script_dir/validate-issue-templates.sh"
repo_root="$(cd "$script_dir/../.." && pwd)"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

expect_success() {
  local dir="$1"
  local label="$2"

  if ! bash "$validator" "$dir" >/dev/null 2>&1; then
    echo "통과해야 하는 템플릿이 실패했습니다: $label" >&2
    exit 1
  fi
}

expect_failure() {
  local dir="$1"
  local label="$2"

  if bash "$validator" "$dir" >/dev/null 2>&1; then
    echo "실패해야 하는 템플릿이 통과했습니다: $label" >&2
    exit 1
  fi
}

new_fixture() {
  local name="$1"

  mkdir -p "$workdir/$name"
  echo "$workdir/$name"
}

# 저장소의 실제 템플릿은 항상 통과해야 한다.
expect_success "$repo_root/.github/ISSUE_TEMPLATE" "저장소의 실제 이슈 템플릿"

# 제목 접두사가 비면 실패한다. 이 PR 이전의 task.yml 상태다.
dir="$(new_fixture missing-prefix)"
printf 'name: 작업\ntitle: ""\nbody: []\n' >"$dir/task.yml"
expect_failure "$dir" "제목 기본값이 빈 템플릿"

# title 키 자체가 없어도 실패한다.
dir="$(new_fixture missing-key)"
printf 'name: 작업\nbody: []\n' >"$dir/task.yml"
expect_failure "$dir" "title 키가 없는 템플릿"

# CONTRIBUTING.md 1절 표에 없는 type이면 실패한다.
dir="$(new_fixture unknown-type)"
printf 'name: 작업\ntitle: "feature: 설명"\nbody: []\n' >"$dir/task.yml"
expect_failure "$dir" "type 목록에 없는 접두사"

# YAML 문법이 깨지면 실패한다.
dir="$(new_fixture broken-yaml)"
printf 'name: 작업\ntitle: "feat: "\nbody:\n  - type: markdown\n   attributes: {}\n' >"$dir/task.yml"
expect_failure "$dir" "YAML 문법이 깨진 템플릿"

# config.yml은 이슈 폼이 아니므로 제목 규칙에서 제외한다.
dir="$(new_fixture config-only)"
printf 'blank_issues_enabled: false\ncontact_links: []\n' >"$dir/config.yml"
printf 'name: 버그\ntitle: "fix: "\nbody: []\n' >"$dir/bug.yml"
expect_success "$dir" "config.yml이 섞인 템플릿"

# 템플릿이 하나도 없으면 실패한다. 디렉터리를 통째로 지운 경우를 잡는다.
dir="$(new_fixture empty)"
expect_failure "$dir" "템플릿이 없는 디렉터리"

echo "이슈 템플릿 검사 테스트를 모두 통과했습니다."
