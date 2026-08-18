#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// "Где ты открыт?" — "Извини, я не совсем понял вопрос."
//
// The agent answered questions about its own environment with philosophy
// instead of facts: it said it was "здесь, в этом чате" while sitting in a
// real directory it could have listed, and the run counter showed инстр. 0 -
// not one tool called. Questions about the workspace, git or GitHub are
// exactly the ones a tool answers best, and it used none.
//
// Two causes, both in the system prompt:
//
//   1. "КОГДА ИНСТРУМЕНТЫ НЕ НУЖНЫ" told it to answer in words for any
//      "вопрос о твоих возможностях". A model reads "где ты открыт" as a
//      question about itself, so that clause swallowed the whole category.
//      There was no rule anywhere saying an environment question means a tool.
//
//   2. The prompt named the working directory but said nothing about what was
//      in it, so the model had no facts to answer with even when it wanted to.
//
// This test pins the prompt text, and then drives the real agent against a
// scripted provider to prove the tool actually runs and its output reaches
// the answer.
//
// Run: node tests/t_env_questions.js

'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const net = require('net');
const os = require('os');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const AGENT = path.join(ROOT, 'agent', 'zen-agent.js');
const SRC = fs.readFileSync(AGENT, 'utf8');

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 1. the prompt tells it to reach for a tool ───────────────────────
console.log('the prompt routes environment questions to tools');

check('there is an explicit rule that an environment question means a tool',
  /ВОПРОС О СРЕДЕ = ВСЕГДА ИНСТРУМЕНТ/.test(SRC));

check('"где ты" is mapped to workspace_info',
  /"где ты"[^\n]*workspace_info/.test(SRC),
  'this is the exact question that got "я не совсем понял вопрос"');

check('"твоя рабочая сессия где" is mapped',
  /рабочая сессия[^\n]*workspace_info/.test(SRC),
  'the second question in the same screenshot');

for (const [label, re] of [
  ['git / repository', /git_status/],
  ['branch',           /"какая ветка"[^\n]*git_branch/],
  ['GitHub',           /гитхабе[^\n]*git_status/],
  ['directory listing',/list_dir/]
]) {
  check(`${label} has a routing entry`, re.test(SRC));
}

check('it is told not to answer with "не совсем понял"',
  /не отвечай "я не совсем понял вопрос"/.test(SRC),
  'the model produced that exact sentence');

check('it is told it is not merely "in this chat"',
  /просто в этом чате/.test(SRC),
  'it claimed to have no location while running in a real directory');

check('an unclear-but-environment question still calls workspace_info first',
  /СНАЧАЛА вызови workspace_info/.test(SRC),
  'asking back is worse than showing the real path');

check('answers about the environment must quote concrete values',
  /называй КОНКРЕТИКУ/.test(SRC));

// The narrowing that caused it.
console.log('\nthe old catch-all no longer swallows these questions');
check('"вопрос о твоих возможностях" no longer waives tools outright',
  !/вопрос о твоих возможностях или что угодно, на что можно ответить словами/.test(SRC),
  'that clause is what made an environment question look like small talk');
check('the no-tools case is scoped to things that are not about the environment',
  /Только если сообщение вообще не касается среды/.test(SRC));
check('greetings still need no tool', /Привет/.test(SRC));
check('the empty-answer guard is still there',
  /Никогда не отвечай пустотой/.test(SRC));

// ── 2. the prompt carries real facts ─────────────────────────────────
console.log('\nthe prompt carries live facts about the workspace');
check('a workspace snapshot is built', /ЧТО СЕЙЧАС В РАБОЧЕЙ ПАПКЕ/.test(SRC));
// The snapshot is framed differently once the github preset is on, so assert
// the behaviour it must keep, not one exact sentence.
check('the snapshot is framed for the github preset too',
  /ЛОКАЛЬНАЯ ПАПКА \(справочно/.test(SRC));
check('it includes the absolute path', /Полный путь: \$\{WORKSPACE_ROOT\}/.test(SRC));
check('it counts directories and files', /Папок: \$\{dirs\.length\}/.test(SRC) && /Файлов: \$\{files\.length\}/.test(SRC));
check('it reports whether this is a git repo and which branch',
  /Git-репозиторий: \$\{isRepo/.test(SRC));
check('the snapshot does not replace the tools',
  /всё равно вызови инструмент/.test(SRC),
  'a stale snapshot must not become the answer');
check('the tool it names follows the active preset',
  /PRESETS\.active\.includes\('github'\)[\s\S]{0,400}github_list \/ github_commits \/ github_read[\s\S]{0,200}Обычно это list_dir \/ git_status/.test(SRC),
  'naming local tools unconditionally overrode the github preset');
check('an unreadable workspace is reported rather than crashing the prompt',
  /сейчас не читается/.test(SRC));
check('the snapshot is wired into the prompt',
  /\$\{envFacts\}/.test(SRC));

// ── 3. drive the real agent ──────────────────────────────────────────
// Same harness as t_chat_live.js: a fake curl answers as the provider, so the
// whole loop runs for real - prompt, parse, tool dispatch, final answer.
console.log('\nend to end: asking "где ты открыт" runs a tool');

const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'zen-env-'));
const BIN = path.join(TMP, 'bin');
const SCRIPT = path.join(TMP, 'script.json');
const CALLS = path.join(TMP, 'calls.log');
fs.mkdirSync(BIN);
// Something recognisable in the workspace, so the snapshot has content.
fs.mkdirSync(path.join(TMP, 'проект-альфа'));
fs.writeFileSync(path.join(TMP, 'README.md'), '# test\n');

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
const step = script[i] || script[script.length - 1] || { body: '{}' };
process.stdout.write(step.body || '');
`);
fs.chmodSync(path.join(BIN, 'curl'), 0o755);

const reply = content => JSON.stringify({
  id: 'x', model: 'laguna-s-2.1-free',
  choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
  usage: { prompt_tokens: 5, completion_tokens: 5, total_tokens: 10 }
});

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const p = srv.address().port;
      srv.close(() => resolve(p));
    });
  });
}

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
  if (started.status !== 202 || !started.body?.run) return { ok: false, why: 'POST -> ' + started.status };
  const id = started.body.run.id;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    await sleep(250);
    const polled = await request(port, 'GET', '/api/agent/run/' + id);
    const run = polled.body?.run;
    if (!run) continue;
    const over = typeof run.done === 'boolean' ? run.done
      : !['queued', 'running', 'awaiting_approval'].includes(run.status);
    if (over) return { ok: true, status: run.status, answer: run.answer, error: run.error, events: run.events || [] };
  }
  return { ok: false, why: 'timed out' };
}

(async () => {
  const port = await freePort();
  fs.writeFileSync(SCRIPT, JSON.stringify([{ body: reply('ок') }]));

  const child = spawn(process.execPath, [AGENT, '--no-dash'], {
    cwd: TMP,
    env: { ...process.env,
      PATH: BIN + path.delimiter + process.env.PATH,
      HOME: TMP,
      MCP_PORT: String(port),
      ZEN_BIND_HOST: '127.0.0.1',
      ZEN_OPEN_BROWSER: '0',
      ZEN_WORKSPACE: TMP,
      MAX_STEPS: '6'
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
    if (!up) { console.log('agent did not start:\n' + log.slice(-1500)); process.exit(1); }

    // The model answers the way the prompt asks it to: a tool call, then prose.
    fs.writeFileSync(path.join(TMP, 'cursor'), '0');
    fs.writeFileSync(SCRIPT, JSON.stringify([
      { body: reply('Смотрю, где я нахожусь.\nTOOL_JSON:{"tool":"workspace_info","args":{}}') },
      { body: reply('Я работаю в папке ' + TMP + '. В ней 1 подпапка и 1 файл.') }
    ]));
    const r = await ask(port, 'Где ты открыт');

    check('the run finishes', r.ok, r.why);
    check('a tool was actually started',
      (r.events || []).some(e => e.type === 'tool_started' && e.tool === 'workspace_info'),
      'events: ' + (r.events || []).map(e => e.type + (e.tool ? ':' + e.tool : '')).join(', '));
    check('the tool succeeded',
      (r.events || []).some(e => e.type === 'tool_finished' && e.ok !== 'false'));
    check('the answer names the real path',
      (r.answer || '').includes(TMP),
      'answer=' + JSON.stringify((r.answer || '').slice(0, 160)));
    check('the answer is not the old brush-off',
      !/не совсем понял/i.test(r.answer || ''));

    // What the provider actually received.
    const calls = fs.readFileSync(CALLS, 'utf8').trim().split('\n').filter(Boolean).map(l => JSON.parse(l));
    const sys = calls.map(c => (c.body.messages || []).find(m => m.role === 'system')?.content || '').filter(Boolean);
    check('the provider was called', calls.length > 0);
    check('the system prompt carried the routing rule',
      sys.some(s => s.includes('ВОПРОС О СРЕДЕ = ВСЕГДА ИНСТРУМЕНТ')));
    check('the system prompt carried the live snapshot',
      sys.some(s => s.includes('ЧТО СЕЙЧАС В РАБОЧЕЙ ПАПКЕ')));
    check('the snapshot listed the real directory',
      sys.some(s => s.includes('проект-альфа')),
      'the model should see what is actually in the folder');
    check('the snapshot reported the absolute path',
      sys.some(s => s.includes(TMP)));

    // The result of the tool has to come back to the model, or the second
    // round is answered blind.
    const second = calls[1];
    if (second) {
      const txt = JSON.stringify(second.body.messages || []);
      check('the tool result was fed back for the next round',
        /Результат MCP-инструмента|workspace_info/i.test(txt));
    } else {
      check('there was a second round after the tool', false, 'only ' + calls.length + ' provider call(s)');
    }
  } finally {
    try { process.kill(-child.pid, 'SIGKILL'); } catch {}
    try { child.kill('SIGKILL'); } catch {}
  }

  console.log();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
