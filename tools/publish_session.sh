#!/usr/bin/env bash
# Publish the live session's connection details so the panel can show them.
#
# The job summary already prints the address, user and password, but it is
# prose meant for a human with a browser: not machine-readable, and on a phone
# it is several taps behind the Actions UI. The panel is where the session is
# started, so it is where the credentials belong.
#
# They go to an orphan branch, session-state, as session.json. An orphan branch
# keeps this churn out of the history of main and lets the file be replaced
# wholesale every time without ever conflicting.
#
# WHO CAN READ THIS
#   On a public repository the branch is public, and so is the password in it.
#   That is the same exposure as the run summary, which is also public - but it
#   is worth being explicit, because a password on a web page feels different
#   from one in a log. Everything here dies with the runner within six hours,
#   and the VNC password is regenerated per run. If that is not acceptable,
#   make the repository private; the panel reads it with the same token either
#   way.
#
# TWO DESKS AT ONCE
#   Pass slot=linux, slot=windows or slot=agent and the entry lands in its own
#   file - session-linux.json, session-windows.json, session-agent.json - so
#   the two desks and the agent never overwrite each other. Without a slot the
#   old single session.json is used, which keeps the older workflows working
#   unchanged.
#
#   The panel reads all three and shows whichever are live.
#
# Usage: publish_session.sh key=value ...
#   Recognised keys are passed straight through to JSON, so a new session type
#   can add a field without touching this script.

set -uo pipefail

BRANCH="session-state"
FILE="session.json"

# A slot is a routing instruction, not data: pull it out of the arguments
# before they become JSON.
ARGS=()
for arg in "$@"; do
  case "$arg" in
    slot=linux)   FILE="session-linux.json" ;;
    slot=windows) FILE="session-windows.json" ;;
    slot=agent)   FILE="session-agent.json" ;;
    slot=*)       ;;   # unknown slot: ignore rather than write a stray file
    *)            ARGS+=("$arg") ;;
  esac
done
set -- ${ARGS+"${ARGS[@]}"}

# A unique staging file per invocation. A fixed /tmp/session.json is shared by
# every caller on the machine, and two publishes running at once overwrote each
# other's payload - caught by a local race test, where the Windows entry ended
# up carrying the Linux address.
STAGE="$(mktemp -t session.XXXXXX.json)"
trap 'rm -f "$STAGE"' EXIT

python3 - "$@" <<'PY' > "$STAGE"
import json, os, sys, datetime

data = {}
for arg in sys.argv[1:]:
    if '=' not in arg:
        continue
    key, value = arg.split('=', 1)
    if value != '':
        data[key] = value

data.setdefault('startedAt', datetime.datetime.now(datetime.timezone.utc)
                .replace(microsecond=0).isoformat().replace('+00:00', 'Z'))
data.setdefault('state', 'live')
data['runId'] = os.environ.get('GITHUB_RUN_ID', '')
data['runNumber'] = os.environ.get('GITHUB_RUN_NUMBER', '')
data['repo'] = os.environ.get('GITHUB_REPOSITORY', '')
# Write bytes, not text.
#
# Python on the Windows runner defaults stdout to cp1252, and the moment a
# value contains Cyrillic - "Стол Windows" - print() raises UnicodeEncodeError,
# the staging file ends up empty and the script reports "nothing to publish"
# and exits 0. The step goes green and no address is ever published. That is
# exactly what happened: the Windows desk was up and reachable, and the panel
# showed nothing. Measured in the run log, not guessed.
sys.stdout.buffer.write(
    json.dumps(data, ensure_ascii=False, indent=2).encode('utf-8'))
sys.stdout.buffer.write(b"\n")
PY

if [ ! -s "$STAGE" ]; then
  echo "publish_session: nothing to publish" >&2
  exit 0
fi

WORK="$(mktemp -d)"
cd "$WORK" || exit 0

# A shallow clone of one branch, or a fresh orphan when it does not exist yet.
if git clone -q --depth 1 --branch "$BRANCH" \
    "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" state 2>/dev/null; then
  cd state || exit 0
else
  git clone -q --depth 1 \
    "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" state || exit 0
  cd state || exit 0
  git checkout -q --orphan "$BRANCH"
  git rm -rqf . 2>/dev/null || true
fi

# Only clear an entry this run owns.
#
# Every session calls this with state=ended on the way out, and the file is a
# single mailbox, so a finishing job would happily stamp "ended" over a
# different session that is still live - which is what happened: a cancelled
# agent erased the record of a running desktop, and the panel then reported no
# session while the desktop was serving fine.
if grep -q '"state": *"ended"' "$STAGE" 2>/dev/null && [ -f "$FILE" ]; then
  OWNER_RUN=$(python3 -c "
import json,sys
try:
    print(json.load(open('$FILE')).get('runId',''))
except Exception:
    print('')
" 2>/dev/null)
  if [ -n "$OWNER_RUN" ] && [ "$OWNER_RUN" != "${GITHUB_RUN_ID:-}" ]; then
    echo "publish_session: $FILE belongs to run $OWNER_RUN, not ${GITHUB_RUN_ID:-?} - left alone"
    exit 0
  fi
fi

cp "$STAGE" "$FILE"
git config user.email "session@symbiosis"
git config user.name  "Session state"
git add "$FILE"

if git diff --cached --quiet; then
  echo "publish_session: unchanged"
  exit 0
fi

git commit -q -m "session $(date -u '+%Y-%m-%d %H:%M:%S')"

# NOT a force push any more.
#
# Two desks publish to this branch at the same time, and -f made the second
# writer erase the first: the Linux desk cloned the branch before the Windows
# desk existed, then force-pushed its own single-file tree over it. Measured -
# session-windows.json was written by a step that reported success, and the
# branch afterwards held only session-linux.json.
#
# So: fetch, replay this one file on top of whatever is there now, push
# normally, and retry if someone else got in between. Each desk only ever
# touches its own file, so the merge is trivial and cannot conflict.
pushed=0
for attempt in 1 2 3 4 5; do
  if git push -q origin "HEAD:$BRANCH" 2>/dev/null; then pushed=1; break; fi

  # Rejected: someone else pushed. Take their tree, put our file back on it.
  if git fetch -q origin "$BRANCH" 2>/dev/null; then
    git reset -q --hard FETCH_HEAD
  else
    # The branch does not exist yet and the push still failed; nothing to
    # rebase onto, so just try again.
    sleep $((attempt * 2))
    continue
  fi
  cp "$STAGE" "$FILE"
  git add "$FILE"
  git commit -q -m "session $(date -u '+%Y-%m-%d %H:%M:%S')" 2>/dev/null || true
  sleep $((attempt * 2))
done

if [ "$pushed" = 1 ]; then
  echo "publish_session: published $FILE to $BRANCH"
else
  echo "publish_session: push failed after retries (the desk still works)" >&2
fi
