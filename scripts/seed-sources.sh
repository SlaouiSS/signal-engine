#!/usr/bin/env sh
# Signal Engine — seed the initial curated source set (docs/03-technical-spec.md Section 17.3:
# "seed (optional sample sources/interests for a first run)"; `make seed`).
#
# This is a small, explicit, opt-in client of the existing public API — it creates sources the same
# way a user would (`POST /api/v1/sources`), never touching the database directly. It is
# deliberately NOT a Flyway migration: the concrete source list is still an open product decision
# (docs/02-functional-spec.md Section 17, Q1), so it must stay something a developer chooses to run,
# not permanent, unconditional schema history applied to every environment.
#
# Idempotent: the backend has no built-in duplicate-source detection (Q2 is open;
# backend/src/main/resources/db/migration/V3__source.sql has no UNIQUE(type, reference)), so this
# script fetches the currently configured sources first and skips any whose `reference` already
# matches one on file, rather than relying on the API to reject a repeat.
#
# Every source is added enabled (the API's default) and with type "http" — the only source type the
# backend currently collects from (signal-engine.ingestion.http-collector-source-types). No area-of-
# interest association is set: `Source` has no such field yet (docs/02-functional-spec.md Section 17,
# Q5, open) — the grouping below is for human readability only and is not sent to the API.
#
# No user Interests are created — only Sources, per the current task's scope.

set -eu

api_base_url="${API_BASE_URL:-http://127.0.0.1:8080/api/v1}"

# type|name|reference — one line per curated source. Verified official URLs/feeds (see the task
# report for how each was confirmed); grouped by area purely as a reading aid.
sources='
http|OpenAI News|https://openai.com/news/rss.xml
http|Anthropic News|https://www.anthropic.com/news
http|Google DeepMind|https://deepmind.google/blog/feed/basic/
http|Microsoft AI|https://news.microsoft.com/source/topics/ai/feed/
http|European Central Bank (ECB)|https://www.ecb.europa.eu/rss/press.html
http|Federal Reserve Board|https://www.federalreserve.gov/feeds/press_all.xml
http|U.S. Securities and Exchange Commission (SEC)|https://www.sec.gov/news/pressreleases.rss
http|European Securities and Markets Authority (ESMA)|https://www.esma.europa.eu/rss.xml
http|Eurostat|https://ec.europa.eu/eurostat/news/news-articles
http|European Commission — Construction ecosystem|https://single-market-economy.ec.europa.eu/sectors/construction_en
http|European Construction Sector Observatory|https://single-market-economy.ec.europa.eu/sectors/construction/european-construction-observatory-eco_en
http|EUR-Lex|https://eur-lex.europa.eu/oj/direct-access.html?locale=en
http|European Commission — Law and Justice|https://commission.europa.eu/law_en
http|European Data Protection Board (EDPB)|https://www.edpb.europa.eu/feed/news_en
http|Vogue Business|https://www.voguebusiness.com/
http|The Business of Fashion|https://www.businessoffashion.com/feed
http|OECD|https://www.oecd.org/en/news-events.html
http|World Bank|https://www.worldbank.org/en/news
http|IMF|https://www.imf.org/en/news
'

response_body=$(mktemp)
request_body=$(mktemp)
trap 'rm -f "$response_body" "$request_body"' EXIT

echo "Fetching currently configured sources from $api_base_url/sources ..."
if ! existing=$(curl -fsS "$api_base_url/sources"); then
  echo "Could not reach $api_base_url/sources — is the backend running? Try 'make up' first." >&2
  exit 1
fi

created=0
skipped=0
failed=0

# A `<<EOF` redirection (not a `| while` pipeline) keeps the loop in the current shell, so the
# counters above are still visible for the summary below.
while IFS='|' read -r type name reference; do
  [ -n "$reference" ] || continue

  case "$existing" in
  *"\"reference\":\"$reference\""*)
    echo "SKIP   (already configured) $name"
    skipped=$((skipped + 1))
    ;;
  *)
    # Written to a file and sent with --data-binary rather than passed as a `-d` argument: a
    # non-ASCII name (e.g. the em dash in "European Commission — ...") survives a shell variable
    # and a redirect intact, but gets mis-encoded if it crosses argv into curl as a literal
    # command-line argument on this platform — a file avoids argv entirely.
    printf '{"type":"%s","name":"%s","reference":"%s"}' "$type" "$name" "$reference" >"$request_body"
    status=$(curl -sS -o "$response_body" -w '%{http_code}' \
      -X POST "$api_base_url/sources" \
      -H 'Content-Type: application/json' \
      --data-binary "@$request_body")
    if [ "$status" = "201" ]; then
      echo "CREATE $name"
      created=$((created + 1))
    else
      echo "FAILED ($status) $name — $(cat "$response_body")" >&2
      failed=$((failed + 1))
    fi
    ;;
  esac
done <<EOF
$sources
EOF

echo "Done: $created created, $skipped already configured, $failed failed."
echo "Re-run this script (or 'make seed') any time — already-configured sources are skipped."

if [ "$failed" -gt 0 ]; then
  exit 1
fi
