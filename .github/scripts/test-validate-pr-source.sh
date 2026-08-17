#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
validator="$script_dir/validate-pr-source.sh"

expect_success() {
  local base_ref="$1"
  local head_ref="$2"

  bash "$validator" "$base_ref" "$head_ref"
}

expect_failure() {
  local base_ref="$1"
  local head_ref="$2"

  if bash "$validator" "$base_ref" "$head_ref" >/dev/null 2>&1; then
    echo "실패해야 하는 브랜치 조합이 통과했습니다: $head_ref -> $base_ref" >&2
    exit 1
  fi
}

expect_success main release/v0.1.0
expect_success main release/v12.34.56
expect_success develop feat/12-notice-crud
expect_success release/v0.1.0 fix/42-release-blocker

expect_failure main develop
expect_failure main feat/12-notice-crud
expect_failure main release/v0.1
expect_failure main release/v01.2.3
