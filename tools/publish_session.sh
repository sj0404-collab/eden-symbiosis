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
# Usage: publish_session.sh key=value ...
#   Recognised keys are passed straight through to JSON, so a new session type
#   can add a field without touching this script.

set -uo pipefail

BRANCH="session-state"
FILE="session.json"

python3 - "$@" <<'PY' > /tmp/session.json
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
print(json.dumps(data, ensure_ascii=False, indent=2))
PY

if [ ! -s /tmp/session.json ]; then
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
if grep -q '"state": *"ended"' /tmp/session.json 2>/dev/null && [ -f "$FILE" ]; then
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

cp /tmp/session.json "$FILE"
git config user.email "session@eden-symbiosis"
git config user.name  "Session state"
git add "$FILE"

if git diff --cached --quiet; then
  echo "publish_session: unchanged"
  exit 0
fi

git commit -q -m "session $(date -u '+%Y-%m-%d %H:%M:%S')"
# Force: this branch is a mailbox, not a history. Racing sessions overwrite
# rather than conflict, and the last writer is the one that is live.
if git push -q -f origin "$BRANCH"; then
  echo "publish_session: published to $BRANCH"
else
  echo "publish_session: push failed (session still works)" >&2
fi
