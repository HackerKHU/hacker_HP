#!/usr/bin/env bash

set -euo pipefail

# 이슈 템플릿의 YAML 유효성과 제목 기본값을 검사한다.
# YAML이 깨지면 GitHub이 템플릿을 조용히 무시하므로 폼이 사라진 것을 아무도 모른다.
# type 목록은 CONTRIBUTING.md 1절 표와 같다.

template_dir="${1:-.github/ISSUE_TEMPLATE}"
type_pattern='^(feat|fix|docs|design|cicd|refactor|test|chore): '

if [[ ! -d "$template_dir" ]]; then
  echo "이슈 템플릿 디렉터리를 찾지 못했습니다: $template_dir" >&2
  exit 1
fi

if command -v python3 >/dev/null 2>&1 && python3 -c 'import yaml' >/dev/null 2>&1; then
  yaml_reader=python3
elif command -v python >/dev/null 2>&1 && python -c 'import yaml' >/dev/null 2>&1; then
  yaml_reader=python
elif command -v ruby >/dev/null 2>&1; then
  yaml_reader=ruby
else
  echo "YAML을 검사하려면 PyYAML이 설치된 python 또는 ruby가 필요합니다." >&2
  exit 2
fi

read_title() {
  local file="$1"

  case "$yaml_reader" in
    ruby)
      ruby -ryaml -e 'data = YAML.safe_load(File.read(ARGV[0])); print(data.is_a?(Hash) ? data.fetch("title", "").to_s : "")' "$file"
      ;;
    *)
      "$yaml_reader" -c 'import sys, yaml
with open(sys.argv[1], encoding="utf-8") as handle:
    data = yaml.safe_load(handle)
sys.stdout.write(str(data.get("title") or "") if isinstance(data, dict) else "")' "$file"
      ;;
  esac
}

shopt -s nullglob
templates=("$template_dir"/*.yml "$template_dir"/*.yaml)

if [[ ${#templates[@]} -eq 0 ]]; then
  echo "검사할 이슈 템플릿이 없습니다: $template_dir" >&2
  exit 1
fi

status=0

for file in "${templates[@]}"; do
  if ! title="$(read_title "$file" 2>/dev/null)"; then
    echo "YAML을 파싱할 수 없습니다: $file" >&2
    status=1
    continue
  fi

  # config.yml은 이슈 폼이 아니라 선택 화면 설정이라 제목 규칙 대상이 아니다.
  case "$(basename "$file")" in
    config.yml | config.yaml) continue ;;
  esac

  if [[ ! "$title" =~ $type_pattern ]]; then
    echo "제목 기본값이 'type: ' 형식이 아닙니다: $file (title: '$title')" >&2
    status=1
  fi
done

exit "$status"
