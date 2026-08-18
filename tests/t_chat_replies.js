#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The chat answered the first message and then went quiet until the question
// was rephrased. Four separate defects produced that one symptom; each is
// pinned here against the real source, so a rewrite that reintroduces any of
// them fails the suite instead of the user.
//
//   1. history.slice() cut between an assistant's tool_calls and the tool
//      results answering them. The provider rejects that window with a 400,
//      so every later message in the session failed.
//   2. The browser ended a run only on completed/failed/aborted; the server
//      writes 'error'. A failed run was polled forever with nothing printed.
//   3. The reaper marked a just-created run stale, because agentBusy is raised
//      a tick after the run object exists.
//   4. The Zen retry was gated on streamMode, which the web console disables -
//      so in the browser one hiccup ended the task, while the CLI recovered.
//
// Run: node tests/t_chat_replies.js

'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const AGENT = fs.readFileSync(path.join(ROOT, 'agent', 'zen-agent.js'), 'utf8');
const HUB = fs.readFileSync(path.join(ROOT, 'agent', 'hub', 'index.html'), 'utf8');

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}

// ── 1. repairToolPairs ───────────────────────────────────────────────
// Extracted from the source rather than reimplemented, so the test cannot
// pass against a function that no longer exists.
function loadRepairToolPairs() {
  const start = AGENT.indexOf('function repairToolPairs(');
  if (start === -1) return null;
  const end = AGENT.indexOf('\nfunction messagesForProvider', start);
  const src = AGENT.slice(start, end === -1 ? undefined : end);
  const ctx = { module: {}, console };
  vm.createContext(ctx);
  vm.runInContext(src + '\nmodule.exports = repairToolPairs;', ctx);
  return ctx.module.exports;
}

console.log('repairToolPairs');
const repair = loadRepairToolPairs();
check('function is present in zen-agent.js', typeof repair === 'function');

if (typeof repair === 'function') {
  // An orphan tool result: the assistant half was sliced away.
  const orphan = [
    { role: 'user', content: 'привет' },
    { role: 'tool', tool_call_id: 'call_gone', name: 'read_file', content: 'данные' },
    { role: 'user', content: 'а теперь второй вопрос' }
  ];
  const fixedOrphan = repair(orphan);
  check('orphan tool result is dropped',
    !fixedOrphan.some(m => m.role === 'tool'),
    JSON.stringify(fixedOrphan));
  check('surrounding messages survive', fixedOrphan.length === 2);

  // A dangling tool_calls: the run died before results came back.
  const dangling = [
    { role: 'user', content: 'сделай' },
    { role: 'assistant', content: 'Читаю файл.', tool_calls: [{ id: 'call_1', function: { name: 'read_file', arguments: '{}' } }] },
    { role: 'user', content: 'ну?' }
  ];
  const fixedDangling = repair(dangling);
  check('unanswered tool_calls are stripped',
    !fixedDangling.some(m => Array.isArray(m.tool_calls) && m.tool_calls.length),
    JSON.stringify(fixedDangling));
  check('the assistant prose is kept',
    fixedDangling.some(m => m.role === 'assistant' && m.content === 'Читаю файл.'));

  // A well-formed pair must pass through untouched.
  const good = [
    { role: 'user', content: 'сделай' },
    { role: 'assistant', content: '', tool_calls: [{ id: 'call_2', function: { name: 'read_file', arguments: '{}' } }] },
    { role: 'tool', tool_call_id: 'call_2', name: 'read_file', content: 'ок' },
    { role: 'assistant', content: 'готово' }
  ];
  const fixedGood = repair(good);
  check('a matched pair is preserved verbatim',
    fixedGood.length === 4 && fixedGood[1].tool_calls.length === 1 && fixedGood[2].tool_call_id === 'call_2',
    JSON.stringify(fixedGood));

  // Partially answered: one of two calls came back.
  const partial = [
    { role: 'assistant', content: '', tool_calls: [
      { id: 'a', function: { name: 'read_file', arguments: '{}' } },
      { id: 'b', function: { name: 'list_dir', arguments: '{}' } }
    ] },
    { role: 'tool', tool_call_id: 'a', name: 'read_file', content: 'ок' }
  ];
  const fixedPartial = repair(partial);
  check('an unanswered call is removed from a partly answered set',
    fixedPartial[0].tool_calls.length === 1 && fixedPartial[0].tool_calls[0].id === 'a',
    JSON.stringify(fixedPartial));

  check('messagesForProvider actually calls it',
    /repairToolPairs\(history\.slice\(-CONFIG\.maxHistory\)\)/.test(AGENT));
}

// ── 2. the client must end on any terminal status ────────────────────
console.log('\nhub run termination');
check('the hard-coded completed/failed/aborted list is gone',
  !/\['completed','failed','aborted'\]\.includes\(run\.status\)/.test(HUB),
  "the server's failure status is 'error', which that list omits");
check('runIsOver() exists', /function runIsOver\(/.test(HUB));
check('it prefers the server-sent done flag', /typeof run\.done === 'boolean'/.test(HUB));
check('its fallback tests the live statuses, not the finished ones',
  /!\['queued','running','awaiting_approval'\]\.includes\(run\.status\)/.test(HUB));
check('the server sends done in every run summary', /done: webRunDone\(run\)/.test(AGENT));
check('a 404 on the run ends it instead of polling forever',
  /r\.status === 404/.test(HUB));
check('an unreachable agent gives up rather than hanging',
  /pollFails >= 30/.test(HUB));
check('endRun is idempotent', /function endRun\(run\)\{\s*\n\s*if \(finished\) return;/.test(HUB.replace(/\r/g, '')));

// The client's own runIsOver, lifted out of the page and run against every
// status the server can actually write. A grep proves the code changed; this
// proves it behaves.
function loadRunIsOver() {
  const start = HUB.indexOf('function runIsOver(');
  if (start === -1) return null;
  const end = HUB.indexOf('\nfunction endRun', start);
  const src = HUB.slice(start, end === -1 ? start + 400 : end);
  const ctx = { module: {} };
  vm.createContext(ctx);
  vm.runInContext(src + '\nmodule.exports = runIsOver;', ctx);
  return ctx.module.exports;
}
const runIsOver = loadRunIsOver();
check('runIsOver is extractable and callable', typeof runIsOver === 'function');
if (typeof runIsOver === 'function') {
  // Every status assigned to a run anywhere in zen-agent.js, so a new one
  // added later without a decision here shows up as a failure.
  const serverStatuses = [...AGENT.matchAll(/(?:run|r)\.status = '([a-z_]+)'/g)].map(m => m[1]);
  check('the server writes a status this test knows about',
    serverStatuses.every(s => ['queued', 'running', 'awaiting_approval', 'completed', 'error'].includes(s)),
    'unhandled: ' + [...new Set(serverStatuses)].join(', '));

  for (const s of ['queued', 'running', 'awaiting_approval']) {
    check('a ' + s + ' run keeps polling', runIsOver({ status: s, done: false }) === false);
  }
  for (const s of ['completed', 'error']) {
    check('a ' + s + ' run stops the poll', runIsOver({ status: s, done: true }) === true);
  }
  // The exact regression: status 'error' from an agent too old to send `done`.
  check("a legacy 'error' run without done still ends the poll",
    runIsOver({ status: 'error' }) === true,
    'this is the bug - the old list had no "error" in it, so the page polled forever');
  check("a legacy 'running' run without done keeps polling",
    runIsOver({ status: 'running' }) === false);
}

// ── 3. a fresh run must not be reaped ────────────────────────────────
console.log('\nstale-run reaper');
check('the agent is claimed synchronously, before setImmediate',
  /agentBusy = true;\s*\n\s*WEB_AGENT_RUN_CONTEXT = run;\s*\n\s*setImmediate/.test(AGENT),
  'raising the flag inside the loop leaves a window where a new POST kills the run');
check('the active run is skipped regardless of agentBusy',
  /if \(WEB_AGENT_RUN_CONTEXT === r\) continue;/.test(AGENT));
check('a run younger than 5s is never stale',
  /Date\.now\(\) - \(r\.startedMs \|\| Date\.parse\(r\.createdAt\) \|\| 0\) < 5000/.test(AGENT));
check('launchWebRun records startedMs', /startedMs: Date\.now\(\)/.test(AGENT));
check('the busy flag is released in launchWebRun too',
  /agentBusy = false;\s*\n\s*run\.approval = null;/.test(AGENT),
  'a throw before agentLoop() is entered would otherwise wedge the agent at busy');

// ── 4. retry must not depend on streaming ────────────────────────────
console.log('\nprovider retry');
check("the Zen fallback no longer requires CONFIG.streamMode",
  !/currentProvider === 'zen' && CONFIG\.streamMode\) res = await callZenWithRetry/.test(AGENT),
  'the web console disables streaming, so that gate skipped the retry in the browser');
check('a Zen failure retries with the fallback model',
  /if \(currentProvider === 'zen'\) \{[\s\S]{0,400}callZenWithRetry/.test(AGENT));

// ── 5. an empty reply must be reported, not swallowed ────────────────
console.log('\nempty replies');
check("the stream placeholder is not treated as an answer",
  /text\.trim\(\) === 'Модель вернула пустой ответ\.'/.test(AGENT));
check('the retry nudge is removed from history again',
  /const at = history\.indexOf\(nudge\); if \(at !== -1\) history\.splice\(at, 1\);/.test(AGENT));
check('two empty replies produce a real message',
  /вернула пустой ответ дважды подряд/.test(AGENT));
check('the model name is named in it', /Модель \$\{currentModel\}/.test(AGENT));

// ── 6. reset must not poison the next run ────────────────────────────
console.log('\nreset');
const resetBlock = AGENT.slice(AGENT.indexOf("'/api/agent/reset'"), AGENT.indexOf("'/api/agent/reset'") + 2000);
check('reset lowers abortRequested', /abortRequested = false;/.test(resetBlock),
  'otherwise the next task stops at step 0 with "Задача остановлена пользователем"');
check('reset clears the provider abort hook', /activeProviderAbort = null;/.test(resetBlock));

// ── 7. finished runs are eventually forgotten ────────────────────────
console.log('\nrun bookkeeping');
check('pruneWebRuns exists', /const pruneWebRuns = \(\) =>/.test(AGENT));
check('it is called when a run finishes', /pruneWebRuns\(\);\s*\n\s*\}\s*\n\s*\}\);/.test(AGENT));
check('the TTL is configurable', /ZEN_WEB_RUN_TTL_MS/.test(AGENT));

console.log();
if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
console.log('all checks passed');
