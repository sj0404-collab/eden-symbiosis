#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Three things:
//
//   GitHub without a checkout - every git tool here needed a clone first
//   (git_clone into work/, set_workspace, edit, commit, push). On a runner
//   that is minutes and gigabytes before a one-line change, and the clone is
//   discarded when the session ends. The REST API edits in place: a write is
//   already a commit, so there is nothing left to push.
//
//   Presets - a way of working stated once instead of retyped every task:
//   "here is the token, work straight on GitHub, do not clone anything".
//   Appended to the system prompt, saved across restarts.
//
//   Tables - the hub already renders markdown tables; the model was never
//   told to produce them, so comparisons came back as long lists.
//
// Run: node tests/t_github_and_presets.js

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

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 1. the module ────────────────────────────────────────────────────
console.log('the GitHub API client');

let mod = null;
try { mod = require(path.join(ROOT, 'lib', 'github-api')); } catch {}
check('lib/github-api.js loads', !!mod && typeof mod.GitHubApi === 'function');

if (mod) {
  const { GitHubApi, normaliseRepo } = mod;
  check('owner/name is accepted', normaliseRepo('a/b', '') === 'a/b');
  check('a full URL is accepted', normaliseRepo('https://github.com/a/b.git', '') === 'a/b');
  check('a trailing slash is tolerated', normaliseRepo('a/b/', '') === 'a/b');
  check('nonsense is rejected rather than guessed', normaliseRepo('nonsense', '') === null);
  check('a default is used when nothing is passed', normaliseRepo('', 'x/y') === 'x/y');

  const api = new GitHubApi(() => '', () => '');
  const methods = ['readFile', 'writeFile', 'deleteFile', 'list', 'commitFiles', 'search',
    'commits', 'branches', 'createBranch', 'pullRequest', 'repoInfo', 'myRepos', 'runs', 'dispatch'];
  const missing = methods.filter(m => typeof api[m] !== 'function');
  check('every operation is implemented', missing.length === 0, 'missing: ' + missing.join(', '));

  // Without a token these must say so, not throw something opaque.
  await0(api);
  async function await0(a) {
    try {
      await a.repoInfo({ repo: 'a/b' });
      check('a missing token is reported', false, 'it resolved instead');
    } catch (e) {
      check('a missing token is reported clearly', /токен|token/i.test(e.message), e.message);
    }
  }

  const src = fs.readFileSync(path.join(ROOT, 'lib', 'github-api.js'), 'utf8');
  check('a write sends the existing sha, so a concurrent change is refused',
    /sha \? \{ sha \} : \{\}/.test(src),
    'without it GitHub would silently overwrite someone else\'s edit');
  check('a 404 on read means "new file" for a write, not an error',
    /if \(!\/404\/\.test\(e\.message\)\) throw e/.test(src));
  check('multi-file commits go through the git data API, not one commit per file',
    /git\/trees/.test(src) && /git\/commits/.test(src),
    'the contents API would leave the branch broken between commits');
  check('the API error message is passed through',
    /GitHub \$\{res\.statusCode\}: \$\{msg\}/.test(src),
    '"Resource not accessible" names a missing scope; a bare 404 does not');
}

// ── 2. wiring into the agent ─────────────────────────────────────────
console.log('\nthe agent exposes the tools');
for (const t of ['github_read', 'github_write', 'github_list', 'github_commit_files',
                 'github_search', 'github_commits', 'github_branches', 'github_pr',
                 'github_runs', 'github_run_workflow']) {
  check(`${t} is registered`, new RegExp(`${t}:`).test(SRC));
}
check('the client is built lazily',
  /function githubApi\(\)/.test(SRC) && /GITHUB_API_CACHE/.test(SRC),
  'the token can arrive after start-up');
check('the session repository is detected from the git remote',
  /function detectSessionRepo/.test(SRC),
  'so a task need not repeat owner/name every call');
check('SYMBIOSIS_REPO can override it', /SYMBIOSIS_REPO/.test(SRC));
check('the system prompt explains that a write is already a commit',
  /это СРАЗУ коммит/.test(SRC));

// ── 3. presets ───────────────────────────────────────────────────────
console.log('\npresets');
check('presets are appended to the system prompt', /\$\{presetPrompt\(\)\}/.test(SRC));
check('they persist to disk', /PRESETS_FILE/.test(SRC) && /savePresets/.test(SRC));
check('the built-in github preset forbids cloning',
  /Не вызывай git_clone/.test(SRC));
check('it lists the github_ tools to use instead', /github_commit_files/.test(SRC));
check('there is a tables preset', /BUILT_IN_PRESETS[\s\S]{0,2000}tables:/.test(SRC));
check('there is a local-only preset', /BUILT_IN_PRESETS[\s\S]{0,2500}local:/.test(SRC));
check('a custom preset can be saved', /preset_save/.test(SRC));
check('an unknown preset id is refused', /Нет пресета/.test(SRC));
check('a preset id is validated', /\^\[\\\\w-\]\{2,32\}\$/.test(SRC) || /\[\\w-\]\{2,32\}/.test(SRC));
check('there is a /preset command', /lower === '\/preset'/.test(SRC));
check('/preset is matched before nothing shadows it',
  SRC.indexOf("lower === '/preset'") < SRC.indexOf("lower === '/autoswitch'"));
check('presets are loaded at start-up', /loadPresets\(\);/.test(SRC));

// ── 4. tables ────────────────────────────────────────────────────────
console.log('\ntables');
check('the prompt asks for tables when comparing things', /ТАБЛИЦЫ:/.test(SRC));
check('it shows the expected markdown shape', /\| Что \| Значение \|/.test(SRC));
check('it says not to table a single fact', /Одиночный факт таблицей не оформляй/.test(SRC));
check('the hub renders tables', /<table>/.test(HUB) && /<th>/.test(HUB));

// ── 5. hub UI ────────────────────────────────────────────────────────
console.log('\nthe hub offers presets');
check('there is a presets panel', /id="presets"/.test(HUB));
check('it can toggle one', /function setPreset/.test(HUB));
check('it can save a new one', /function savePreset/.test(HUB));
check('a preset label is inserted as text, not HTML',
  /head\.textContent = p\.label/.test(HUB),
  'a label is user-supplied; innerHTML here would be an injection');

// ── 6. live ──────────────────────────────────────────────────────────
console.log('\nend to end');

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
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
        x.on('end', () => { try { resolve(JSON.parse(d)); } catch { resolve({}); } });
      });
    r.on('error', reject);
    if (data) r.write(data);
    r.end();
  });
}

(async () => {
  const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'ghp-'));
  const BIN = path.join(TMP, 'bin');
  const CALLS = path.join(TMP, 'calls.log');
  fs.mkdirSync(BIN);
  // A fake curl records the request so the system prompt can be inspected.
  fs.writeFileSync(path.join(BIN, 'curl'), `#!/usr/bin/env node
const fs=require('fs');const a=process.argv.slice(2);const d=a.find(x=>x.startsWith('@'));
let b='';if(d){try{b=fs.readFileSync(d.slice(1),'utf8')}catch{}}
fs.appendFileSync(${JSON.stringify(CALLS)}, b + '\\n---\\n');
process.stdout.write(JSON.stringify({choices:[{message:{role:'assistant',content:'ок'}}],usage:{}}));`);
  fs.chmodSync(path.join(BIN, 'curl'), 0o755);

  const port = await freePort();
  const child = spawn(process.execPath, [AGENT, '--no-dash'], {
    cwd: ROOT,
    env: { PATH: BIN + path.delimiter + process.env.PATH, MCP_PORT: String(port),
           ZEN_BIND_HOST: '127.0.0.1', ZEN_OPEN_BROWSER: '0', HOME: TMP, ZEN_WORKSPACE: TMP },
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
      const status = await call(port, 'GET', '/mcp/status');
      const names = (status.tools || []).map(t => t.name);
      check('all 14 github tools are advertised',
        names.filter(n => n.startsWith('github_')).length === 14,
        String(names.filter(n => n.startsWith('github_')).length));
      check('the preset tools are advertised',
        names.filter(n => n.startsWith('preset_')).length === 3);

      const list = await call(port, 'GET', '/api/presets');
      check('the built-in presets are served',
        (list.presets || []).some(p => p.id === 'github'));

      await call(port, 'POST', '/api/presets', { id: 'github', on: true });
      const after = await call(port, 'GET', '/api/presets');
      check('a preset can be switched on', (after.active || []).includes('github'));

      const bad = await call(port, 'POST', '/api/presets', { save: true, id: 'плохой id', text: 'x' });
      check('an invalid preset id is refused', !!bad.error);

      await call(port, 'POST', '/api/presets', { save: true, id: 'my-style', text: 'Отвечай коротко.' });
      const saved = await call(port, 'GET', '/api/presets');
      check('a custom preset is saved and active',
        (saved.presets || []).some(p => p.id === 'my-style') && (saved.active || []).includes('my-style'));

      // The whole point: it has to reach the model.
      await call(port, 'POST', '/api/agent/run', { input: 'привет', session: 'default' });
      await sleep(2500);
      const log = fs.existsSync(CALLS) ? fs.readFileSync(CALLS, 'utf8') : '';
      const first = log.split('\n---\n')[0];
      let sys = '';
      try { sys = (JSON.parse(first).messages || []).find(m => m.role === 'system').content; } catch {}
      check('the preset text reaches the system prompt',
        sys.includes('без клонирования'),
        'a preset that does not reach the model does nothing');
      check('the custom preset reaches it too', sys.includes('Отвечай коротко.'));
      check('the tables rule reaches it', sys.includes('ТАБЛИЦЫ'));
      check('the github tools are described to the model', sys.includes('github_write'));
    }
  } finally {
    try { process.kill(-child.pid, 'SIGKILL'); } catch {}
    try { child.kill('SIGKILL'); } catch {}
  }

  console.log('\nthe hub still parses');
  let ok = true, err = '';
  try { new vm.Script(HUB.match(/<script>([\s\S]*)<\/script>/)[1]); } catch (e) { ok = false; err = e.message; }
  check('hub script is valid JavaScript', ok, err);

  console.log();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
