#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// End-to-end proof for the "answers once, then silent" report.
//
// The agent reaches its provider by spawning curl, so the whole stack can be
// exercised for real by putting a fake curl on PATH that answers from a local
// script instead of opencode.ai. Nothing is stubbed inside the agent itself:
// this drives the actual HTTP API the phone talks to - POST /api/agent/run,
// then poll GET /api/agent/run/<id> - exactly as agent/hub/index.html does.
//
// Four rounds, each reproducing a way the chat used to go quiet:
//
//   1. a plain answer                       (baseline: this always worked)
//   2. a tool call, then an answer          (leaves tool_calls in history -
//                                            the window that used to 400)
//   3. the provider fails once              (used to end the task in the
//                                            browser, where streaming is off)
//   4. the provider returns an empty body   (used to complete with no text)
//
// The test asserts every round produced a reply. Before the fix, round 3
// returned an error and round 4 returned nothing.
//
// Run: node tests/t_chat_live.js

'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const AGENT = path.join(ROOT, 'agent', 'zen-agent.js');
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'zen-live-'));
const BIN = path.join(TMP, 'bin');
const SCRIPT = path.join(TMP, 'script.json');
const CALLS = path.join(TMP, 'calls.log');
fs.mkdirSync(BIN);

// ── the scripted provider ────────────────────────────────────────────
// One entry per model request, consumed in order. The fake curl reads this
// file fresh each time, so the script can be swapped between rounds.
function setScript(entries) { fs.writeFileSync(SCRIPT, JSON.stringify(entries)); }

const okBody = (content, toolCalls) => JSON.stringify({
  id: 'chatcmpl-test', model: 'laguna-s-2.1-free',
  choices: [{ index: 0, message: toolCalls
    ? { role: 'assistant', content: content || '', tool_calls: toolCalls }
    : { role: 'assistant', content } , finish_reason: 'stop' }],
  usage: { prompt_tokens: 11, completion_tokens: 7, total_tokens: 18 }
});

// A stand-in for curl. It ignores the URL and replies from the script, logging
// what the agent sent so the request window can be inspected afterwards.
fs.writeFileSync(path.join(BIN, 'curl'), `#!/usr/bin/env node
const fs = require('fs');
const argv = process.argv.slice(2);
const dataArg = argv.find(a => a.startsWith('@'));
let body = '';
if (dataArg) { try { body = fs.readFileSync(dataArg.slice(1), 'utf8'); } catch {} }

const script = JSON.parse(fs.readFileSync(${JSON.stringify(SCRIPT)}, 'utf8'));
const state = ${JSON.stringify(path.join(TMP, 'cursor'))};
let i = 0; try { i = parseInt(fs.readFileSync(state, 'utf8'), 10) || 0; } catch {}
fs.writeFileSync(state, String(i + 1));

fs.appendFileSync(${JSON.stringify(CALLS)}, JSON.stringify({ i, body: JSON.parse(body || '{}') }) + '\\n');

const step = script[i] || script[script.length - 1] || { kind: 'ok', body: '{}' };
if (step.kind === 'fail') { process.stderr.write(step.body || 'connection reset'); process.exit(52); }
process.stdout.write(step.body || '');
`);
fs.chmodSync(path.join(BIN, 'curl'), 0o755);

// ── helpers ──────────────────────────────────────────────────────────
const sleep = ms => new Promise(r => setTimeout(r, ms));

function request(port, method, urlPath, payload) {
  return new Promise((resolve, reject) => {
    const data = payload === undefined ? null : Buffer.from(JSON.stringify(payload));
    const req = http.request({ host: '127.0.0.1', port, method, path: urlPath,
      headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {} },
      res => {
        let raw = '';
        res.on('data', c => raw += c);
        res.on('end', () => {
          let parsed = null; try { parsed = JSON.parse(raw); } catch {}
          resolve({ status: res.statusCode, body: parsed, raw });
        });
      });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

// The page's own termination test, lifted out of agent/hub/index.html so this
// test polls the way the phone really polls. Reimplementing it here would let
// a broken page pass a green suite - which is precisely what happened: the
// server marked failed runs 'error' and the page waited for 'failed'.
function loadClientRunIsOver() {
  const hub = fs.readFileSync(path.join(ROOT, 'agent', 'hub', 'index.html'), 'utf8');
  const start = hub.indexOf('function runIsOver(');
  if (start !== -1) {
    const end = hub.indexOf('\nfunction endRun', start);
    const src = hub.slice(start, end === -1 ? start + 400 : end);
    const sandbox = { module: {} };
    require('vm').createContext(sandbox);
    require('vm').runInContext(src + '\nmodule.exports = runIsOver;', sandbox);
    return sandbox.module.exports;
  }
  // No runIsOver: this is the old page. Recreate its actual condition so the
  // test observes the old behaviour rather than a charitable version of it.
  const list = hub.match(/\[('completed','failed','aborted')\]\.includes\(run\.status\)/);
  if (list) return run => ['completed', 'failed', 'aborted'].includes(run.status);
  return run => !['queued', 'running', 'awaiting_approval'].includes(run.status);
}
const clientRunIsOver = loadClientRunIsOver();

// Exactly what the browser does: start a run, then poll until it is over.
async function ask(port, text, timeoutMs = 45000) {
  const started = await request(port, 'POST', '/api/agent/run', { input: text, session: 'default' });
  if (started.status !== 202 || !started.body?.run) {
    return { ok: false, why: 'POST /api/agent/run -> ' + started.status + ' ' + started.raw.slice(0, 200) };
  }
  const id = started.body.run.id;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await sleep(250);
    const polled = await request(port, 'GET', '/api/agent/run/' + id);
    if (polled.status === 404) return { ok: false, why: 'run vanished (404)' };
    const run = polled.body?.run;
    if (!run) continue;
    if (clientRunIsOver(run)) return { ok: true, status: run.status, answer: run.answer, error: run.error, run };
  }
  return { ok: false, why: 'timed out after ' + (timeoutMs / 1000) + 's — the page never saw the run end' };
}

// ── run ──────────────────────────────────────────────────────────────
let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}

// Ask the OS for a free port instead of guessing one. Two suites running back
// to back used overlapping ranges, and the agent silently moves to the next
// port when its own is taken (EADDRINUSE), so the test then polled a port
// nothing was listening on and blamed the agent.
function freePort() {
  const net = require('net');
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
  });
}

(async () => {
  const port = await freePort();
  setScript([{ kind: 'ok', body: okBody('Готово.') }]);

  const child = spawn(process.execPath, [AGENT, '--no-dash'], {
    cwd: TMP,
    env: { ...process.env,
      PATH: BIN + path.delimiter + process.env.PATH,
      HOME: TMP,
      MCP_PORT: String(port),
      ZEN_BIND_HOST: '127.0.0.1',
      ZEN_OPEN_BROWSER: '0',
      ZEN_WORKSPACE: TMP,
      MAX_STEPS: '6',
      MAX_PROVIDER_RETRIES: '3'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let log = '';
  child.stdout.on('data', d => log += d.toString());
  child.stderr.on('data', d => log += d.toString());

  const stop = () => { try { process.kill(-child.pid, 'SIGKILL'); } catch {} try { child.kill('SIGKILL'); } catch {} };

  try {
    // Wait for the port.
    let up = false;
    for (let i = 0; i < 80 && !up; i++) {
      await sleep(250);
      try { const r = await request(port, 'GET', '/mcp/status'); up = r.status === 200; } catch {}
    }
    if (!up) { console.log('agent did not start:\n' + log.slice(-2000)); process.exit(1); }
    console.log('agent up on ' + port + '\n');

    // ── round 1: a plain answer ──────────────────────────────────────
    console.log('round 1 — plain answer');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([{ kind: 'ok', body: okBody('Первый ответ.') }]);
    const r1 = await ask(port, 'привет');
    check('the run finishes', r1.ok, r1.why);
    check('it answers', !!(r1.answer || '').trim(), JSON.stringify(r1).slice(0, 300));

    // ── round 2: a tool call, then an answer ─────────────────────────
    // This is the round that poisons history: the assistant's tool_calls and
    // the tool results have to stay paired for every later request.
    console.log('\nround 2 — tool call, then answer');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([
      { kind: 'ok', body: okBody('Смотрю папку.\nTOOL_JSON: {"tool":"list_dir","args":{"path":"."}}') },
      { kind: 'ok', body: okBody('В папке пусто.') }
    ]);
    const r2 = await ask(port, 'посмотри папку');
    check('the run finishes', r2.ok, r2.why);
    check('it answers after the tool', !!(r2.answer || '').trim(), JSON.stringify(r2).slice(0, 300));

    // ── round 3: the provider fails past its retry budget ────────────
    // MAX_PROVIDER_RETRIES is 3, so three failures exhaust callZenWithRetry
    // and the error reaches the loop. The outer fallback used to be gated on
    // CONFIG.streamMode - which launchWebRun turns off for every web run - so
    // in the browser the task ended here while the terminal recovered.
    console.log('\nround 3 — provider fails past its retry budget');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([
      { kind: 'fail', body: 'curl: (52) empty reply from server' },
      { kind: 'fail', body: 'curl: (52) empty reply from server' },
      { kind: 'fail', body: 'curl: (52) empty reply from server' },
      { kind: 'ok', body: okBody('Ответ после восстановления.') }
    ]);
    const r3 = await ask(port, 'третий вопрос');
    check('the run finishes', r3.ok, r3.why);
    check('the web run retries the way the terminal does',
      (r3.answer || '').includes('восстановления'),
      'answer=' + JSON.stringify(r3.answer) + ' error=' + JSON.stringify(r3.error));

    // ── round 3b: the provider never recovers ────────────────────────
    // The run legitimately ends in status 'error'. The page has to notice.
    // It used to wait for 'failed', a status the server never writes, so the
    // spinner ran until the tab was reloaded - the reported silence.
    console.log('\nround 3b — provider never recovers, run ends in error');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([{ kind: 'fail', body: 'curl: (52) empty reply from server' }]);
    const r3b = await ask(port, 'вопрос в сломанную сеть', 25000);
    check('the page sees the run end', r3b.ok,
      (r3b.why || '') + ' — the server finished it, but the client never noticed');
    check('the failure is shown to the user',
      !!((r3b.answer || '') + (r3b.error || '')).trim(),
      JSON.stringify(r3b).slice(0, 300));

    // ── round 4: an empty body ───────────────────────────────────────
    console.log('\nround 4 — provider returns nothing');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([{ kind: 'ok', body: okBody('') }]);
    const r4 = await ask(port, 'четвёртый вопрос');
    check('the run finishes', r4.ok, r4.why);
    check('an empty reply is reported rather than silently completed',
      !!(r4.answer || r4.error || '').trim(),
      'the console would print nothing at all here');
    check('the message names the cause',
      /пуст/i.test((r4.answer || '') + (r4.error || '')),
      JSON.stringify(r4.answer || r4.error));

    // ── round 5: the agent is still usable afterwards ────────────────
    // The real complaint: it worked once, then every later message failed.
    console.log('\nround 5 — still answering after all of the above');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([{ kind: 'ok', body: okBody('Пятый ответ, всё ещё работаю.') }]);
    const r5 = await ask(port, 'пятый вопрос');
    check('the run finishes', r5.ok, r5.why);
    check('no 409 "agent is busy" after a failed round',
      !/busy/i.test(r5.why || ''), r5.why);
    check('it answers', (r5.answer || '').includes('Пятый ответ'), JSON.stringify(r5).slice(0, 300));

    // ── round 6: a second message sent while the first is starting ───
    // The reaper keyed off agentBusy, which was raised a tick after the run
    // object appeared. A POST landing inside that window found a 'queued' run
    // with nobody owning it, declared it stale and marked it failed - while
    // the agent went on working on it. The tab then polled a run the server
    // had already written off.
    console.log('\nround 6 — a second message races the first');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([{ kind: 'ok', body: okBody('Ответ на гонку.') }]);
    const first = await request(port, 'POST', '/api/agent/run', { input: 'первый', session: 'default' });
    const second = await request(port, 'POST', '/api/agent/run', { input: 'второй', session: 'default' });
    check('the first run is accepted', first.status === 202, 'got ' + first.status);
    check('the second is refused with 409, not allowed to reap the first',
      second.status === 409,
      'got ' + second.status + ' — a second run was admitted while one was live');

    if (first.body?.run) {
      const id = first.body.run.id;
      let settled = null;
      for (let i = 0; i < 120 && !settled; i++) {
        await sleep(250);
        const polled = await request(port, 'GET', '/api/agent/run/' + id);
        const run = polled.body?.run;
        if (run && clientRunIsOver(run)) settled = run;
      }
      check('the raced run still completes', !!settled && settled.status === 'completed',
        settled ? 'status=' + settled.status + ' error=' + settled.error : 'never finished');
      check('it was not reaped as stale',
        !/не завершил|сброшен/i.test(String(settled?.error || '')),
        String(settled?.error || ''));
    }

    // ── round 7: the agent survives all of it ────────────────────────
    console.log('\nround 7 — still answering at the end');
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    setScript([{ kind: 'ok', body: okBody('Последний ответ.') }]);
    const r7 = await ask(port, 'последний вопрос');
    check('the run finishes', r7.ok, r7.why);
    check('it answers', (r7.answer || '').includes('Последний ответ'),
      JSON.stringify(r7).slice(0, 300));

    // ── the request window stayed valid ──────────────────────────────
    console.log('\nrequest windows');
    const calls = fs.readFileSync(CALLS, 'utf8').trim().split('\n').filter(Boolean).map(l => JSON.parse(l));
    check('the provider was actually called', calls.length > 0, 'the fake curl was never used');
    let orphans = 0;
    for (const call of calls) {
      const msgs = call.body?.messages || [];
      const ids = new Set();
      for (const m of msgs) if (m.role === 'assistant' && Array.isArray(m.tool_calls)) for (const t of m.tool_calls) ids.add(t.id);
      for (const m of msgs) if (m.role === 'tool' && m.tool_call_id && !ids.has(m.tool_call_id)) orphans++;
    }
    check('no request carried an orphan tool result', orphans === 0,
      orphans + ' orphan(s) — the provider answers those with a 400 and the chat goes quiet');
  } finally {
    stop();
  }

  console.log();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
