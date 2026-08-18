#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Two things: one secret instead of six, and versions that number themselves.
//
// ONE KEY
//   OPENROUTER_API_KEY, HF_TOKEN, HUGGINGFACE_TOKEN, GITHUB_TOKEN,
//   GITHUB_MODELS_TOKEN, OPENAI_API_KEY - six names to get exactly right, and
//   getting one wrong showed up only as a provider quietly listed as
//   unavailable. SYMBIOSIS_KEY takes them all at once and routes each token by
//   its issuer-assigned prefix.
//
// AUTO VERSIONS
//   versionCode, versionName and SHELL_VERSION were three hand-written copies
//   of "2.0". Android refuses to install an APK whose versionCode is not
//   greater than the installed one, so a rebuilt panel could silently fail to
//   update, and the version string said nothing about what was in the build.
//   All three now derive from git.
//
// Run: node tests/t_one_key_and_versions.js

'use strict';
const { execFileSync, spawn } = require('child_process');
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
const GRADLE = fs.readFileSync(path.join(ROOT, 'panel-app', 'app', 'build.gradle.kts'), 'utf8');
const ACTIVITY = fs.readFileSync(
  path.join(ROOT, 'panel-app', 'app', 'src', 'main', 'java', 'dev', 'symbiosis', 'panel', 'MainActivity.kt'), 'utf8');
const WF_AGENT = fs.readFileSync(path.join(ROOT, '.github', 'workflows', 'agent.yml'), 'utf8');
const WF_APK = fs.readFileSync(path.join(ROOT, '.github', 'workflows', 'panel-apk.yml'), 'utf8');

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 1. routing, executed ─────────────────────────────────────────────
// The classifier is lifted out of the source rather than reimplemented, so a
// change to the real prefixes fails here instead of quietly passing.
console.log('one key: token routing');

function loadClassifier() {
  const start = SRC.indexOf('function symbiosisKeys()');
  if (start === -1) return null;
  const end = SRC.indexOf('\n/** What the one key resolved to', start);
  const body = SRC.slice(start, end === -1 ? start + 2000 : end);
  const ctx = { module: {}, process: { env: {} } };
  vm.createContext(ctx);
  vm.runInContext(
    'let SYMBIOSIS_KEY_CACHE = null;\n' + body + '\nmodule.exports = (v) => { SYMBIOSIS_KEY_CACHE = null; process.env.SYMBIOSIS_KEY = v; return symbiosisKeys(); };',
    ctx);
  return ctx.module.exports;
}
const classify = loadClassifier();
check('symbiosisKeys() exists and is callable', typeof classify === 'function');

if (typeof classify === 'function') {
  const all = classify([
    'sk-or-v1-0000000000000000',
    'hf_00000000000000000000',
    'ghp_00000000000000000000',
    'sk-ant-api03-0000000000',
    'sk-proj-000000000000000'
  ].join('\n'));
  check('sk-or- goes to OpenRouter', all.openrouter.startsWith('sk-or-'));
  check('hf_ goes to Hugging Face', all.huggingface.startsWith('hf_'));
  check('ghp_ goes to GitHub', all.github.startsWith('ghp_'));
  check('sk-ant- goes to Anthropic', all.anthropic.startsWith('sk-ant-'));
  check('a bare sk- goes to OpenAI, not OpenRouter',
    all.openai.startsWith('sk-proj-') && all.openai !== all.openrouter,
    'sk-or- and sk-ant- are narrower and must be matched first');

  check('github_pat_ is recognised too',
    classify('github_pat_11ABCDEFG0000000000').github.startsWith('github_pat_'));
  check('commas work as well as newlines',
    classify('sk-or-v1-0000000000000000, hf_00000000000000000000').huggingface !== '');
  check('whitespace and blank lines are tolerated',
    classify('\n  sk-or-v1-0000000000000000  \n\n').openrouter !== '');
  check('an unrecognised token is reported rather than dropped silently',
    classify('total-nonsense-value').unknown.length === 1,
    'a typo must be visible');
  check('a value is never echoed back in full',
    !classify('total-nonsense-value').unknown[0].includes('nonsense'));
  check('nothing configured yields nothing', classify('').openrouter === '');
}

// ── 2. wiring ────────────────────────────────────────────────────────
console.log('\none key: wiring');
check('OpenRouter falls back to the shared key',
  /function openRouterKey\(\)[^\n]*symbiosisKeys\(\)\.openrouter/.test(SRC));
check('Hugging Face falls back to the shared key',
  /function huggingFaceToken\(\)[^\n]*symbiosisKeys\(\)\.huggingface/.test(SRC));
check('GitHub falls back to the shared key',
  /symbiosisKeys\(\)\.github/.test(SRC));
check('the dedicated variables still take priority',
  /process\.env\.OPENROUTER_API_KEY \|\| symbiosisKeys/.test(SRC),
  'an existing setup must keep working');
check('the workflow passes SYMBIOSIS_KEY',
  /SYMBIOSIS_KEY="\$\{\{ secrets\.SYMBIOSIS_KEY \}\}"/.test(WF_AGENT));
check('the old per-provider secrets are still honoured',
  /HF_TOKEN="\$\{\{ secrets\.HF_TOKEN \}\}"/.test(WF_AGENT));
check('the status report never exposes a value',
  /symbiosisKeyReport/.test(SRC) && !/masked: .*symbiosisKeys/.test(SRC));

// ── 3. versions come from git ────────────────────────────────────────
console.log('\nversions number themselves');
check('versionCode is no longer a literal',
  !/versionCode = 2\b/.test(GRADLE) && /versionCode = panelVersionCode/.test(GRADLE));
check('versionName is no longer a literal',
  !/versionName = "2\.0"/.test(GRADLE) && /versionName = panelVersionName/.test(GRADLE));
check('versionCode is the commit count',
  /git\("rev-list", "--count", "HEAD"\)/.test(GRADLE),
  'monotonic by construction, which is what Android requires to upgrade');
check('versionName carries the short sha',
  /rev-parse", "--short", "HEAD"/.test(GRADLE));
check('an uncommitted tree is marked dirty',
  /\+dirty/.test(GRADLE),
  'otherwise a local build is indistinguishable from the committed one');
check('a checkout without git still builds',
  /\?: 1\b/.test(GRADLE) && /"dev"/.test(GRADLE));
check('the shell reads the version instead of repeating it',
  /BuildConfig\.PANEL_VERSION/.test(ACTIVITY) && !/SHELL_VERSION = "2\.0"/.test(ACTIVITY));
check('the field is generated', /buildConfigField\("String", "PANEL_VERSION"/.test(GRADLE));
check('BuildConfig generation is switched on',
  /buildFeatures\s*\{[^}]*buildConfig = true/.test(GRADLE),
  'AGP 8 ignores buildConfigField without it, and the class would not exist');
check('a stub exists so the fast type-check still resolves it',
  fs.existsSync(path.join(ROOT, 'panel-app', 'stubs', 'BuildConfig.kt')));
check('CI checks out full history',
  /fetch-depth: 0/.test(WF_APK),
  'a shallow clone has one commit, so every build would be version 1');

// The computation, run for real against this repository.
console.log('\nthe version actually computes');
const git = (...a) => { try { return execFileSync('git', a, { cwd: ROOT, encoding: 'utf8' }).trim(); } catch { return ''; } };
const count = parseInt(git('rev-list', '--count', 'HEAD'), 10);
check('the commit count is a sane number', Number.isInteger(count) && count > 0, String(count));

// A shallow clone reports one commit however long the history is, so the
// monotonicity check below is only meaningful with the full history - and
// asserting it anyway would fail for a reason that says nothing about the
// code. This is not hypothetical: CI checks this repository out shallow, and
// that is precisely what the fetch-depth requirement above exists to fix.
const shallow = fs.existsSync(path.join(ROOT, '.git', 'shallow')) ||
  git('rev-parse', '--is-shallow-repository') === 'true';
if (shallow) {
  console.log('  skip the version outranks the old hard-coded 2 — shallow clone, count is always 1');
} else {
  check('it is greater than the old hard-coded 2', count > 2,
    'an APK built now must out-rank the one that said versionCode 2');
}

// ── 4. build identity is visible ─────────────────────────────────────
console.log('\nthe running build identifies itself');
check('the agent reports its build', /function agentBuildInfo/.test(SRC));
check('/api/info carries it', /build: agentBuildInfo\(\)/.test(SRC));
check('it reports when the session started',
  /startedAt: new Date\(Date\.now\(\) - Math\.round\(process\.uptime/.test(SRC),
  'this is how a session older than the fix is spotted');
check('the hub shows it', /id="m-build"/.test(HUB));
check('the hub warns when the code is newer than the session',
  /код новее сессии/.test(HUB),
  'exactly the case where a merged fix looks missing');

// ── 5. live ──────────────────────────────────────────────────────────
console.log('\nend to end');

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
}
function get(port, p) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path: p }, r => {
      let d = '';
      r.on('data', c => d += c);
      r.on('end', () => { try { resolve(JSON.parse(d)); } catch { resolve({}); } });
    }).on('error', reject);
  });
}

(async () => {
  const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'onekey-'));
  const port = await freePort();
  // One secret, three tokens, plus a typo that must be reported.
  const child = spawn(process.execPath, [AGENT, '--no-dash'], {
    cwd: ROOT,
    env: { PATH: process.env.PATH, MCP_PORT: String(port), ZEN_BIND_HOST: '127.0.0.1',
           ZEN_OPEN_BROWSER: '0', HOME: TMP, ZEN_WORKSPACE: TMP,
           SYMBIOSIS_KEY: 'sk-or-v1-fake0000000000000000\nhf_fake0000000000000000\nghp_fake0000000000000000\nbroken-token' },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  try {
    let up = false;
    for (let i = 0; i < 80 && !up; i++) {
      await sleep(250);
      try { await get(port, '/mcp/status'); up = true; } catch {}
    }
    check('the agent started', up);
    if (up) {
      const info = await get(port, '/api/info');
      check('the build is reported', !!(info.build && info.build.version),
        JSON.stringify(info.build));
      check('the build version is derived, not "dev"',
        info.build && info.build.version !== 'dev' && /^\d+\./.test(info.build.version),
        String(info.build && info.build.version));
      check('the key report says it is configured', info.keys && info.keys.configured === true);
      for (const p of ['openrouter', 'huggingface', 'github']) {
        check(`${p} came from the single key`,
          info.keys.providers[p].fromKey === true && info.keys.providers[p].active === true);
      }
      check('the malformed token is reported',
        (info.keys.unrecognised || []).length === 1,
        JSON.stringify(info.keys.unrecognised));
      check('no secret value appears in the response',
        !JSON.stringify(info).includes('fake0000000000000000'),
        'the report must never echo a token');

      // The whole point: one secret enables every provider.
      const models = (await get(port, '/api/models')).models || [];
      for (const p of ['openrouter', 'github', 'huggingface']) {
        const rows = models.filter(m => m.providerId === p);
        check(`${p} models are enabled by the single key`,
          rows.length > 0 && rows.every(m => m.configured === true),
          rows.length ? 'some still disabled' : 'none listed');
      }
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
