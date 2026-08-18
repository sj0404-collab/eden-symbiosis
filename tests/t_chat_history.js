#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The bug behind "it answers once, then goes quiet until I rephrase".
//
// On the OpenRouter path the agent calls tools natively, so history holds an
// assistant message carrying tool_calls followed by role:'tool' results. Two
// things break that pairing:
//
//   * slice(-maxHistory) cuts between the assistant and its results;
//   * a run that dies mid-tool leaves tool_calls with no results at all.
//
// Either way the next request carries an orphan, and an OpenAI-compatible API
// answers 400 - not once, but for every following message in that session,
// because the damage stays in history. Rephrasing appeared to help only
// because a longer or shorter message shifted the window past the broken pair.
//
// This drives the real agent against a stub that enforces the same rule the
// real API does: a 400 for a malformed window. A session that has used a tool
// must keep answering afterwards.
//
// Run: node tests/t_chat_history.js

'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const os = require('os');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const AGENT = path.join(ROOT, 'agent', 'zen-agent.js');
const PRELOAD = path.join(__dirname, 'support', 'openrouter_stub.js');
const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'zen-hist-'));

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── the stub OpenRouter ──────────────────────────────────────────────
// Validates the window exactly as the real API does, then replies from a
// script. Every rejection is recorded, because a single 400 is the failure.
const rejections = [];
const windows = [];
let script = [];
let cursor = 0;

function validate(messages) {
  const declared = new Set();
  for (const m of messages) {
    if (m.role === 'assistant' && Array.isArray(m.tool_calls)) {
      for (const t of m.tool_calls) declared.add(t.id);
    }
  }
  const answered = new Set();
  for (const m of messages) {
    if (m.role !== 'tool') continue;
    if (!m.tool_call_id || !declared.has(m.tool_call_id)) {
      return `Invalid parameter: messages with role 'tool' must be a response to a preceding message with 'tool_calls'. Offending id: ${m.tool_call_id}`;
    }
    answered.add(m.tool_call_id);
  }
  for (const id of declared) {
    if (!answered.has(id)) {
      return `An assistant message with 'tool_calls' must be followed by tool messages responding to each tool_call_id. Missing: ${id}`;
    }
  }
  return null;
}

const stub = http.createServer((req, res) => {
  let raw = '';
  req.on('data', c => raw += c);
  req.on('end', () => {
    let payload = {};
    try { payload = JSON.parse(raw || '{}'); } catch {}
    const messages = payload.messages || [];
    windows.push(messages);

    const problem = validate(messages);
    if (problem) {
      rejections.push(problem);
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: problem, code: 400 } }));
      return;
    }

    const step = script[cursor] || script[script.length - 1] || { content: 'ок' };
    cursor++;
    const message = step.tool_calls
      ? { role: 'assistant', content: step.content || '', tool_calls: step.tool_calls }
      : { role: 'assistant', content: step.content || '' };
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      id: 'gen-test', model: payload.model || 'test/model',
      choices: [{ index: 0, message, finish_reason: step.tool_calls ? 'tool_calls' : 'stop' }],
      usage: { prompt_tokens: 9, completion_tokens: 5, total_tokens: 14 }
    }));
  });
});

function request(port, method, urlPath, payload) {
  return new Promise((resolve, reject) => {
    const data = payload === undefined ? null : Buffer.from(JSON.stringify(payload));
    const req = http.request({ host: '127.0.0.1', port, method, path: urlPath,
      headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {} },
      res => {
        let raw = '';
        res.on('data', c => raw += c);
        res.on('end', () => { let b = null; try { b = JSON.parse(raw); } catch {} resolve({ status: res.statusCode, body: b, raw }); });
      });
    req.on('error', reject);
    if (data) req.write(data);
    req.end();
  });
}

async function ask(port, text, timeoutMs = 40000) {
  const started = await request(port, 'POST', '/api/agent/run', { input: text, session: 'default' });
  if (started.status !== 202 || !started.body?.run) {
    return { ok: false, why: 'POST -> ' + started.status + ' ' + started.raw.slice(0, 200) };
  }
  const id = started.body.run.id;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await sleep(250);
    const polled = await request(port, 'GET', '/api/agent/run/' + id);
    const run = polled.body?.run;
    if (!run) continue;
    const over = typeof run.done === 'boolean' ? run.done
      : !['queued', 'running', 'awaiting_approval'].includes(run.status);
    if (over) return { ok: true, status: run.status, answer: run.answer, error: run.error };
  }
  return { ok: false, why: 'timed out' };
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
  await new Promise(r => stub.listen(0, '127.0.0.1', r));
  const stubPort = stub.address().port;
  const port = await freePort();

  const child = spawn(process.execPath, ['--require', PRELOAD, AGENT, '--no-dash'], {
    cwd: TMP,
    env: { ...process.env,
      PATH: process.env.PATH,
      HOME: TMP,
      MCP_PORT: String(port),
      ZEN_BIND_HOST: '127.0.0.1',
      ZEN_OPEN_BROWSER: '0',
      ZEN_WORKSPACE: TMP,
      ZEN_TEST_OR_PORT: String(stubPort),
      OPENROUTER_API_KEY: 'sk-or-v1-testkeytestkeytestkey00',
      MAX_STEPS: '8',
      // A tight window is the point: the pair must survive being sliced.
      MAX_PROVIDER_RETRIES: '2'
    },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let log = '';
  child.stdout.on('data', d => log += d.toString());
  child.stderr.on('data', d => log += d.toString());

  try {
    let up = false;
    for (let i = 0; i < 80 && !up; i++) {
      await sleep(250);
      try { const r = await request(port, 'GET', '/mcp/status'); up = r.status === 200; } catch {}
    }
    if (!up) { console.log('agent did not start:\n' + log.slice(-2000)); process.exit(1); }

    // Switch the running agent to OpenRouter, the provider with native tools.
    const settings = await request(port, 'POST', '/api/agent/settings', { provider: 'openrouter', model: 'test/model' });
    check('the agent accepts the openrouter provider', settings.status === 200,
      settings.status + ' ' + settings.raw.slice(0, 200));

    console.log('\nround 1 — a native tool call, then an answer');
    cursor = 0;
    script = [
      { content: 'Смотрю папку.', tool_calls: [{ id: 'call_alpha', type: 'function', function: { name: 'list_dir', arguments: '{"path":"."}' } }] },
      { content: 'В папке пусто.' }
    ];
    const r1 = await ask(port, 'посмотри папку');
    check('the run finishes', r1.ok, r1.why);
    check('it answers', !!(r1.answer || '').trim(), JSON.stringify(r1).slice(0, 200));
    check('the window with the tool result was accepted',
      rejections.length === 0, rejections[0]);

    // This is the regression. History now holds the assistant's tool_calls and
    // the tool result. Every later message reuses that window - and once the
    // conversation is longer than maxHistory (25), slice() starts cutting
    // inside it. Enough rounds are run here to walk the cut across the pair.
    console.log('\nrounds 2-24 — the session keeps working as the window slides');
    let firstBroken = 0;
    for (let i = 2; i <= 24; i++) {
      cursor = 0;
      // Every third round uses two tools at once. A multi-call assistant
      // message is the easiest thing for a naive slice to bisect.
      if (i % 3 === 0) {
        script = [
          { content: 'Проверяю.', tool_calls: [
            { id: 'call_' + i + 'a', type: 'function', function: { name: 'list_dir', arguments: '{"path":"."}' } },
            { id: 'call_' + i + 'b', type: 'function', function: { name: 'workspace_info', arguments: '{}' } }
          ] },
          { content: 'Ответ номер ' + i + '.' }
        ];
      } else {
        script = [{ content: 'Ответ номер ' + i + '.' }];
      }
      const r = await ask(port, 'вопрос номер ' + i + ' — ' + 'пожалуйста ответь подробно'.repeat(2));
      const good = r.ok && (r.answer || '').includes('Ответ номер ' + i);
      if (!good && !firstBroken) firstBroken = i;
      check('round ' + i + ' answers', good,
        r.ok ? ('answer=' + JSON.stringify(r.answer) + ' error=' + JSON.stringify(r.error)) : r.why);
    }
    if (firstBroken) {
      console.log('       ↳ the chat went quiet from round ' + firstBroken +
                  ' onward — this is the reported symptom');
    }

    console.log('\nwindow validity');
    check('the provider never rejected a window',
      rejections.length === 0,
      rejections.length + ' rejection(s); first: ' + (rejections[0] || ''));

    // Prove the pairing survived rather than the tool history simply vanishing.
    const withTools = windows.filter(w => w.some(m => m.role === 'tool'));
    check('later requests still carried the tool result',
      withTools.length >= 2,
      'only ' + withTools.length + ' window(s) contained one — the context was dropped, not repaired');

  } finally {
    try { process.kill(-child.pid, 'SIGKILL'); } catch {}
    try { child.kill('SIGKILL'); } catch {}
    stub.close();
  }

  console.log();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
