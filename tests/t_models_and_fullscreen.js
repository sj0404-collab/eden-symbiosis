#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Three complaints, three fixes:
//
//   1. The chat was not full-screen. It rendered as an 85vh iframe inside the
//      desks card, on a page with 12px padding and 90px of bottom padding for
//      the tab bar, so it lost about a fifth of the screen.
//
//   2. Only three models were offered. The hub had ZEN_MODELS copied into it
//      as a literal, and getHubModels() returned the same three - so an
//      OpenRouter key, a GitHub token, a Hugging Face token and any model
//      downloaded into the runner were all invisible and unselectable, even
//      though every one of those providers was already implemented.
//
//   3. Models were swapped silently on a rate limit. Fine on a free tier,
//      wrong for a paid key and meaningless for a local model, which has no
//      quota to hit in the first place.
//
// Run: node tests/t_models_and_fullscreen.js

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
const PANEL = fs.readFileSync(path.join(ROOT, 'docs', 'index.html'), 'utf8');

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}
const sleep = ms => new Promise(r => setTimeout(r, ms));

// ── 1. full screen ───────────────────────────────────────────────────
console.log('the chat is full-screen');

check('it is a fixed overlay, not a box inside the card',
  /id = 'chat-overlay'/.test(PANEL) && /position:fixed;inset:0/.test(PANEL),
  'an iframe in the card inherits the page padding');
check('the old 85vh iframe is gone',
  !/height:85vh/.test(PANEL),
  '85vh inside a padded page is not the screen');
check('it uses dvh so the keyboard does not cover the composer',
  /height:100dvh/.test(PANEL),
  'vh does not follow the collapsing browser chrome on Android');
check('it sits above the page', /z-index:9999/.test(PANEL));
check('the notch is accounted for', /env\(safe-area-inset-top\)/.test(PANEL));
check('the page behind it stops scrolling',
  /document\.body\.style\.overflow = 'hidden'/.test(PANEL));
check('closing restores scrolling',
  /function closeChatOverlay[\s\S]{0,300}document\.body\.style\.overflow = ''/.test(PANEL));
check('the iframe is only rebuilt when the address changes',
  /ov\.dataset\.url !== url/.test(PANEL),
  'a re-render must not reload the hub and lose what was typed');
check('Android back closes the chat rather than the panel',
  /popstate[\s\S]{0,200}chat-overlay[\s\S]{0,80}collapseDesks/.test(PANEL));
check('there is still a way out to a real browser tab',
  /function openChatInBrowser/.test(PANEL));

// ── 2. every model, every provider ───────────────────────────────────
console.log('\nthe model list covers every provider');

check('the hub no longer hard-codes three ids',
  !/const MODELS = \[\s*\['laguna-s-2\.1-free'/.test(HUB),
  'that literal is why the dropdown showed three entries');
check('the hub asks the agent for the list',
  /fetch\('\/api\/models'/.test(HUB));
check('models are grouped by provider', /optgroup/.test(HUB));
check('a provider with no credentials is shown, disabled, with a reason',
  /configured === false/.test(HUB) && /нет ключа/.test(HUB),
  'an omitted model looks like a bug; a disabled one explains itself');
check('choosing a model also switches provider on the server',
  /\/api\/agent\/settings[\s\S]{0,300}provider/.test(HUB),
  'a GitHub id sent to the Zen endpoint is just an error');

check('getHubModels lists OpenRouter', /openRouterFreeModels/.test(SRC) && /'openrouter', 'OpenRouter'/.test(SRC));
check('getHubModels lists GitHub Models', /GITHUB_MODELS/.test(SRC) && /'github', 'GitHub Models'/.test(SRC));
check('getHubModels lists Hugging Face', /HUGGINGFACE_MODELS/.test(SRC) && /'huggingface', 'Hugging Face'/.test(SRC));
check('getHubModels lists local runner models',
  /localAi\.listModels/.test(SRC) && /'local', 'Local AI'/.test(SRC),
  'these are the ones with no quota at all');
check('a model chosen from the CLI is never dropped from the list',
  /выбрана сейчас/.test(SRC));

check('the OpenRouter catalogue is no longer filtered down to :free',
  !/const free = all\.filter\(model => String\(model\.id \|\| ''\)\.endsWith\(':free'\)/.test(SRC),
  'a paid key could reach hundreds of models and saw only the free ones');
check('free models are merely sorted first',
  /mapped\.sort\(\(a, b\) => \(a\.free === b\.free/.test(SRC));

// The stub must answer every call the agent makes, or asking for models throws.
console.log('\nthe local-ai stub answers every call');
const stubBody = SRC.slice(SRC.indexOf('LocalAiManager = class'), SRC.indexOf('// Optional at module-load time'));
const called = [...SRC.matchAll(/localAi\.([a-zA-Z]+)/g)].map(m => m[1]);
const missing = [...new Set(called)].filter(m => !new RegExp(`\\b${m}\\s*\\(`).test(stubBody));
check('no method the agent calls is missing from the stub',
  missing.length === 0,
  'missing: ' + missing.join(', ') + ' — calling one throws a TypeError instead of reporting "unavailable"');

// ── 3. no silent substitution ────────────────────────────────────────
console.log('\nauto-switching is a choice, not a rule');

check('there is an autoSwitchModel setting', /autoSwitchModel:/.test(SRC));
check('it can be turned off from the environment', /ZEN_AUTO_SWITCH/.test(SRC));
check('the Zen fallback respects it',
  /if \(rateLimited && CONFIG\.autoSwitchModel\)/.test(SRC));
check('with it off the same model is retried, and it says so',
  /Автопереключение выключено/.test(SRC));
check('OpenRouter tries only the chosen model when it is off',
  /CONFIG\.autoSwitchModel\s*\?[\s\S]{0,200}:\s*\[model\]/.test(SRC));
check('there is a /autoswitch command', /lower === '\/autoswitch'/.test(SRC));

// Command routing: /auto must not swallow /autoswitch.
const iAutoswitch = SRC.indexOf("lower === '/autoswitch'");
const iAuto = SRC.indexOf("lower === '/auto' ||");
check('/autoswitch is matched before /auto',
  iAutoswitch !== -1 && iAuto !== -1 && iAutoswitch < iAuto,
  'otherwise "/autoswitch off" toggles auto-approval instead');

// Prove the routing, rather than trusting the order.
const route = cmd => {
  const lower = cmd.toLowerCase();
  if (lower === '/autoswitch' || lower.startsWith('/autoswitch ')) return 'autoswitch';
  if (lower === '/auto' || lower.startsWith('/auto ')) return 'auto';
  return 'other';
};
check('"/autoswitch off" routes to autoswitch', route('/autoswitch off') === 'autoswitch');
check('"/auto on" still routes to auto-approval', route('/auto on') === 'auto');

// ── 4. the running agent really serves them ──────────────────────────
console.log('\nend to end: /api/models from a live agent');

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.on('error', reject);
    srv.listen(0, '127.0.0.1', () => { const p = srv.address().port; srv.close(() => resolve(p)); });
  });
}
function get(port, p) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port, path: p }, res => {
      let d = '';
      res.on('data', c => d += c);
      res.on('end', () => { try { resolve(JSON.parse(d)); } catch { resolve({ raw: d.slice(0, 200) }); } });
    }).on('error', reject);
  });
}

(async () => {
  const TMP = fs.mkdtempSync(path.join(os.tmpdir(), 'zen-models-'));
  const port = await freePort();
  const child = spawn(process.execPath, [AGENT, '--no-dash'], {
    cwd: ROOT,
    env: { ...process.env, MCP_PORT: String(port), ZEN_BIND_HOST: '127.0.0.1',
           ZEN_OPEN_BROWSER: '0', HOME: TMP, ZEN_WORKSPACE: TMP },
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
      const m = await get(port, '/api/models');
      const models = m.models || [];
      const provs = new Set(models.map(x => x.providerId));
      check('more than the three Zen models are offered', models.length > 3,
        models.length + ' model(s)');
      check('Zen is present', provs.has('zen'));
      check('OpenRouter is present', provs.has('openrouter'));
      check('GitHub Models is present', provs.has('github'));
      check('Hugging Face is present', provs.has('huggingface'));
      check('every entry says whether it is usable',
        models.every(x => typeof x.configured === 'boolean'));
      check('every entry names its provider',
        models.every(x => !!x.providerId && !!x.providerName));

      // The endpoint that used to throw on the stub.
      const loc = await get(port, '/api/local-ai/models');
      check('/api/local-ai/models responds instead of throwing',
        loc && loc.success === true,
        JSON.stringify(loc).slice(0, 160));
    }
  } finally {
    try { process.kill(-child.pid, 'SIGKILL'); } catch {}
    try { child.kill('SIGKILL'); } catch {}
  }

  // ── 5. both pages still parse ──────────────────────────────────────
  console.log('\nthe pages still parse');
  for (const [label, html] of [['panel', PANEL], ['hub', HUB]]) {
    const s = html.match(/<script>([\s\S]*)<\/script>/);
    let ok = true, err = '';
    try { new vm.Script(s[1]); } catch (e) { ok = false; err = e.message; }
    check(`${label} script is valid JavaScript`, ok, err);
  }

  console.log();
  if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
  console.log('all checks passed');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
