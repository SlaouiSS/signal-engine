#!/usr/bin/env sh
# Signal Engine — guard against floating container image references.
#
# Every container image in the committed Docker configuration must carry an
# explicit tag, never `latest` and never an implicit tag (docs/03-technical-spec.md
# Section 19.3, 20.3; Principal Engineer Code Review finding 8). Runtime/platform
# images are pinned to their documented major/minor line (Section 19.3, item 1);
# tools and the LLM runtime are pinned to an exact release. Dependabot's `docker`
# ecosystem proposes bumps.
#
# Exits non-zero and prints every offending line when a floating reference is found.

set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$repo_root"

targets="docker-compose.yml agents/Dockerfile backend/Dockerfile frontend/Dockerfile"
report=$(mktemp)
trap 'rm -f "$report"' EXIT

for file in $targets; do
  if [ ! -f "$file" ]; then
    printf '%s: expected Docker configuration file not found\n' "$file" >>"$report"
    continue
  fi

  # Consider only lines that actually reference an image: `image:`, `FROM`, `COPY --from=`.
  image_lines=$(grep -nE '^[[:space:]]*(image:[[:space:]]|FROM[[:space:]]|COPY[[:space:]].*--from=)' "$file" || true)
  [ -n "$image_lines" ] || continue

  # 1. an explicit `latest` tag
  printf '%s\n' "$image_lines" | grep -E ':latest([[:space:]]|$)' | while IFS= read -r line; do
    printf '%s: floating `latest` tag -> %s\n' "$file" "$(printf '%s' "$line" | sed 's/^[0-9]*:[[:space:]]*//')"
  done >>"$report"

  # 2. a registry image (has a `/` or a `.`) referenced with no tag at all
  printf '%s\n' "$image_lines" \
    | grep -oE '(image:[[:space:]]*|FROM[[:space:]]+(--platform=[^[:space:]]+[[:space:]]+)?|--from=)[A-Za-z0-9][A-Za-z0-9._/-]*(:[A-Za-z0-9._-]+)?' \
    | sed -E 's/^(image:[[:space:]]*|FROM[[:space:]]+(--platform=[^[:space:]]+[[:space:]]+)?|--from=)//' \
    | while IFS= read -r ref; do
        case "$ref" in
          */*|*.*)
            case "$ref" in
              *:*) : ;;                       # tagged — good
              *) printf '%s: untagged image reference -> %s\n' "$file" "$ref" ;;
            esac ;;
        esac
      done >>"$report"
done

if [ -s "$report" ]; then
  printf 'Floating / unpinned container image reference(s):\n\n' >&2
  cat "$report" >&2
  printf '\nPin every image to an explicit tag (docs/03-technical-spec.md Section 19.3).\n' >&2
  exit 1
fi

printf 'check-container-image-pins: all container images are pinned to explicit tags.\n'
