#!/usr/bin/env bash
#
# Smoke test for the devshell commands the menu advertises.
#
# It exists because `tfs` was broken and nothing noticed. Its help promised
# "run one slice, e.g. tfs spark/function/features/string", and it ignored its
# argument entirely and hardcoded a path, so it ran whatever the annotation
# pointed at. SailCorpusTest names that command as *the* way to sanity-check a
# number before reading anything into it — so the one tool for not trusting a
# figure was itself lying.
#
# Checking that a command is on PATH would have caught none of that: it was
# present, it exited zero, and it printed a report. So the cheap ones are run
# and their output is checked for the specific way each one failed.
#
# Deliberately only checked for existence: `t` is the suite CI runs as its own
# job, `tf` is the whole corpus at several thousand scenarios, `sail-server`
# blocks by design, and `f` rewrites files, which a CI job has no business doing.

set -uo pipefail

failures=0
ok()  { printf '  \033[32m✓\033[0m %-14s %s\n' "$1" "${2-}"; }
bad() { printf '  \033[31m✗\033[0m %-14s %s\n' "$1" "$2"; failures=$((failures + 1)); }

echo "Commands the menu advertises"
for cmd in t tf tfs c sail-server f publish-local menu check-commands; do
  if command -v "$cmd" >/dev/null 2>&1; then ok "$cmd"; else bad "$cmd" "not on PATH"; fi
done

echo
echo "Commands cheap enough to run"

if c >/dev/null 2>&1; then ok "c" "compiles"; else bad "c" "compile failed"; fi

# The sibling Scala template consumes this, so a break here stays invisible
# until that project fails instead of this one.
if publish-local >/dev/null 2>&1; then
  ok "publish-local" "published"
else
  bad "publish-local" "publishLocal failed"
fi

# The regression this file was written for. `struct` is the smallest slice in
# the corpus at 13 scenarios, and the count is the tell: a `tfs` that ignores
# its argument runs the whole of function/ and reports thousands.
slice=$(tfs spark/function/features/struct 2>&1)
count=$(printf '%s\n' "$slice" | sed -n 's/.*report — \([0-9][0-9]*\) scenarios.*/\1/p' | head -1)
if [ -z "$count" ]; then
  bad "tfs" "no report; the slice did not run"
elif [ "$count" -gt 100 ]; then
  bad "tfs" "reported $count scenarios: the path argument was ignored"
else
  ok "tfs" "ran $count scenarios, not the whole corpus"
fi

# Its refusal to run on a bad path is half of what makes it usable.
if tfs spark/function/features/does-not-exist >/dev/null 2>&1; then
  bad "tfs" "accepted a slice that does not exist"
else
  ok "tfs" "rejects an unknown slice"
fi

# The header that lets a saved report say what produced it.
if printf '%s\n' "$slice" | grep -q "sail .* · corpus v.* · spark-connect-client-jvm"; then
  ok "report" "states its provenance"
else
  bad "report" "header lost its versions"
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "All commands behaved."
else
  echo "$failures command(s) misbehaved."
fi
exit "$failures"
