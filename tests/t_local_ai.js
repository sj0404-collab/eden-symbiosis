#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Running a model inside the runner, so there is no quota to hit.
//
// Every online provider here is limited: Zen's free tier runs out, GitHub
// Models is rate-limited, OpenRouter's free ids are throttled and a paid key
// costs money. A GGUF downloaded into the runner and served by llama.cpp has
// none of that - it is on the same machine as the agent.
//
// Before this, lib/local-ai did not exist at all: zen-agent fell back to a
// stub, so the 'local' provider was permanently unavailable and the local
// model endpoints answered "not installed" - or worse, threw, because the
// stub was missing seven of the methods the agent calls.
//
// HF_TOKEN was also never passed into the runner. Only GITHUB_TOKEN was, so
// Hugging Face models showed as "нужен ключ" no matter what was in the
// repository secrets, and a large GGUF download had no token to lift the
// anonymous rate limit.
//
// Run: node tests/t_local_ai.js

'use strict';
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');
const net = require('net');
const os = require('os');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const AGENT = path.join(ROOT, 'agent', 'zen-agent.js');
const SRC = fs.readFileSync(AGENT, 'utf8');
const HUB = fs.readFileSync(path.join(ROOT, 'agent', 'hub', 'index.html'), 'utf8');
const WF = fs.readFileSync(path.join(ROOT, '.github', 'workflows', 'agent.yml'), 'utf8');

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 1. the module exists and satisfies the whole contract ────────────
console.log('the local-ai module');

let mod = null;
try { mod = require(path.join(ROOT, 'lib', 'local-ai')); } catch (e) { /* reported below */ }
check('lib/local-ai.js loads', !!mod && typeof mod.LocalAiManager === 'function',
  'zen-agent requires ../lib/local-ai and falls back to a stub without it');

if (mod) {
  const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'lai-unit-'));
  const m = new mod.LocalAiManager({ storageRoot: () => TMP });

  // Every method zen-agent.js calls must exist, or the endpoint throws.
  const called = [...new Set([...SRC.matchAll(/localAi\.([a-zA-Z]+)\s*\(/g)].map(x => x[1]))];
  const missing = called.filter(name => typeof m[name] !== 'function');
  check('it implements every method the agent calls', missing.length === 0,
    'missing: ' + missing.join(', '));

  check('the catalogue is not empty', Array.isArray(mod.CATALOG) && mod.CATALOG.length >= 3);
  check('every entry names a repo and a specific file',
    mod.CATALOG.every(e => e.repo && e.file && e.file.endsWith('.gguf')));
  check('every entry declares its download size',
    mod.CATALOG.every(e => Number(e.sizeMb) > 0));
  check('the models fit a runner disk',
    mod.CATALOG.every(e => e.sizeMb < 6000),
    'a runner has ~14 GB free and the user waits for the download');
  check('there is a small model for a quick start',
    mod.CATALOG.some(e => e.sizeMb < 1500));

  check('no models are reported before anything is downloaded',
    Array.isArray(m.listModels()) && m.listModels().length === 0);
  check('the runtime is reported as missing rather than assumed',
    m.listRuntimes()[0] && m.listRuntimes()[0].installed === false);
  check('publicConfig reports the module as available',
    m.publicConfig().available === true,
    'the stub reports false; that is how the agent tells them apart');
  check('an unknown model id is refused, not silently ignored',
    m.startDownload({ modelId: 'no-such-model' }).status === 'error');
  check('configure persists a choice',
    m.configure({ selectedModel: 'qwen2.5-coder-3b' }).selectedModel === 'qwen2.5-coder-3b');

  // A partial download must never be advertised as a usable model.
  check('downloads are written to .part first',
    fs.readFileSync(path.join(ROOT, 'lib', 'local-ai.js'), 'utf8').includes("target + '.part'"),
    'an interrupted job would otherwise leave a truncated .gguf');
}

// ── 2. the agent exposes it ──────────────────────────────────────────
console.log('\nthe agent wires it up');
check('there is a one-call prepare endpoint', /'\/api\/local-ai\/prepare'/.test(SRC),
  'download runtime + download weights + start, in one step');
check('preparing switches the agent onto the local provider',
  /currentProvider = 'local'/.test(SRC));
check('there is a stop endpoint', /'\/api\/local-ai\/stop'/.test(SRC));
check('the endpoints degrade politely when the module is absent',
  /typeof localAi\.prepare !== 'function'/.test(SRC));

// ── 3. credentials actually reach the runner ─────────────────────────
console.log('\nthe workflow passes the tokens');
check('HF_TOKEN is passed to the agent', /HF_TOKEN="\$\{\{ secrets\.HF_TOKEN \}\}"/.test(WF),
  'without it Hugging Face models stay disabled and large downloads are rate-limited');
check('OPENROUTER_API_KEY is passed', /OPENROUTER_API_KEY="\$\{\{ secrets\.OPENROUTER_API_KEY \}\}"/.test(WF));
check('GITHUB_TOKEN is still passed', /GITHUB_TOKEN="\$GH_TOKEN"/.test(WF));
check('the local server port is fixed for the session', /ZEN_LOCAL_AI_PORT/.test(WF));
check('the agent reads HF_TOKEN or HUGGINGFACE_TOKEN',
  /process\.env\.HF_TOKEN \|\| process\.env\.HUGGINGFACE_TOKEN/.test(SRC));

// ── 4. the hub offers it ─────────────────────────────────────────────
console.log('\nthe hub offers local models');
check('there is a panel for local models', /id="local"/.test(HUB));
check('it lists the catalogue', /\/api\/local-ai\/catalog/.test(HUB));
check('one button downloads and starts', /prepareLocal/.test(HUB) && /\/api\/local-ai\/prepare/.test(HUB));
check('a running model can be stopped', /stopLocal/.test(HUB));
check('size and context are shown before committing to a download',
  /ГБ.*k|sizeMb\/1024/.test(HUB),
  'these decide whether the wait is worth it');
check('elapsed time is shown while it downloads',
  /Math\.round\(\(Date\.now\(\)-started\)\/1000\)/.test(HUB),
  'a multi-minute download with no feedback looks like a hang');
check('a missing HF token is explained rather than left as a silent failure',
  /HF_TOKEN не задан/.test(HUB));
check('it says plainly that this removes the limits',
  /без лимитов/.test(HUB));

// ── 5. live agent ────────────────────────────────────────────────────
console.log('\nend to end');

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}
function call(port, method, p, body) {
  return new Promise((resolve, reject) => {
    const data = body ? Buffer.from(JSON.stringify(body)) : null;
    const r = http.request({ host: '127.0.0.1', port, path: p, method,
      headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {} },
      x => {
        let d = '';
        x.on('data', c => d += c);
        x.on('end', () => { try { resolve({ s: x.statusCode, b: JSON.parse(d) }); } catch { resolve({ s: x.statusCode, b: {} }); } });
      });
    r.on('error', reject);
    if (data) r.write(data);
    r.end();
  });
}

(async () => {
  const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'lai-e2e-'));
  const port = await freePort();
  const child = spawn(process.execPath, [AGENT, '--no-dash'], {
    cwd: ROOT,
    env: { ...process.env, MCP_PORT: String(port), ZEN_BIND_HOST: '127.0.0.1',
           ZEN_OPEN_BROWSER: '0', HOME: TMP, ZEN_WORKSPACE: TMP,
           HF_TOKEN: 'hf_faketokenforthistest' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  try {
    let up = false;
    for (let i = 0; i < 80 && !up; i++) {
      await sleep(250);
      try { await call(port, 'GET', '/mcp/status'); up = true; } catch {}
    }
    check('the agent started', up);
    if (up) {
      const cat = await call(port, 'GET', '/api/local-ai/catalog');
      check('the catalogue is served', (cat.b.catalog || []).length >= 3,
        JSON.stringify(cat.b).slice(0, 120));

      const st = await call(port, 'GET', '/api/local-ai/status');
      check('status reports the real state, not "unavailable"',
        st.b.success === true && typeof st.b.runtimeInstalled === 'boolean');
      check('the HF token is picked up from the environment',
        st.b.hfTokenConfigured === true,
        'the workflow passes it; the module has to read it');

      // The endpoint that threw on the stub.
      const models = await call(port, 'GET', '/api/local-ai/models');
      check('/api/local-ai/models responds', models.b.success === true);

      const hf = (await call(port, 'GET', '/api/models')).b.models
        .filter(m => m.providerId === 'huggingface');
      check('Hugging Face models are enabled once the token is set',
        hf.length > 0 && hf.every(m => m.configured === true),
        'with no token they are listed disabled instead');

      const bad = await call(port, 'POST', '/api/local-ai/prepare', { modelId: 'nope' });
      check('preparing an unknown model fails with a clear message',
        bad.s === 400 && /Неизвестная модель/.test(bad.b.error || ''),
        JSON.stringify(bad.b).slice(0, 120));

      const stop = await call(port, 'POST', '/api/local-ai/stop', {});
      check('stopping is safe even when nothing is running', stop.b.success === true);
    }
  } finally {
    try { process.kill(-child.pid, 'SIGKILL'); } catch {}
    try { child.kill('SIGKILL'); } catch {}
  }

  console.log('\nthe hub still parses');
  const s = HUB.match(/<script>([\s\S]*)<\/script>/);
  let ok = true, err = '';
  try { new vm.Script(s[1]); } catch (e) { ok = false; err = e.message; }
  check('hub script is valid JavaScript', ok, err);

  console.log();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
