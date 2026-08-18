#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The agent's chat has to open inside the app, not in the browser.
//
// Two separate things had to be true, and both were false:
//
//   1. The panel called window.open(agentUrl, '_blank'). Inside a WebView that
//      leaves the app; on a phone the user lands in Chrome with the session
//      token sitting in the address bar and in history.
//
//   2. Even once the page stopped doing that, the shell would have pushed the
//      URL out anyway: shouldOverrideUrlLoading treated only github.io and
//      ngrok as "ours", while agent.yml publishes the hub through a
//      cloudflared quick tunnel - *.trycloudflare.com.
//
// Run: node tests/t_chat_in_app.js

'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.join(__dirname, '..');
const PANEL = fs.readFileSync(path.join(ROOT, 'docs', 'index.html'), 'utf8');
const SHELL = fs.readFileSync(
  path.join(ROOT, 'panel-app', 'app', 'src', 'main', 'java', 'dev', 'symbiosis', 'panel', 'MainActivity.kt'),
  'utf8'
);

let failed = 0;
function check(name, cond, detail) {
  if (cond) { console.log('  ok   ' + name); return; }
  failed++;
  console.log('  FAIL ' + name + (detail ? '\n       ' + detail : ''));
}

// ── 1. the page opens the chat in place ──────────────────────────────
console.log('panel: the chat opens in the app');

check('the "Открыть чат" button expands the embedded view',
  /onclick="expandDesk\('agent'\)">Открыть чат</.test(PANEL),
  'it used to be window.open(agentUrl, "_blank")');

check('there is a deskOpen === \'agent\' branch that renders an iframe',
  /deskOpen === 'agent' && agent/.test(PANEL) &&
  /deskOpen === 'agent'[\s\S]{0,2200}<iframe/.test(PANEL));

// The chat started as an 85vh iframe inside the desks card. That card sits on
// a page with 12px padding and 90px of bottom padding for the tab bar, so it
// was never actually full-screen. It is now a fixed overlay; the height check
// lives in t_models_and_fullscreen.js, and this one only guards against a
// regression back into the card.
check('the chat is not boxed inside the card any more',
  !/height:85vh/.test(PANEL) && /id = 'chat-overlay'/.test(PANEL),
  'an iframe inside the card inherits the page padding');

check('opening a session no longer pops the chat out',
  !/if \(s\.agentUrl\) \{ window\.open/.test(PANEL),
  'the session card called window.open(s.agentUrl)');

check('the session card routes the chat into the panel',
  /if \(s\.agentUrl\) \{ openDeskInPanel\('agent'\); return; \}/.test(PANEL));

check('openDeskInPanel exists and switches to the page holding the card',
  /function openDeskInPanel\(slot\)[\s\S]{0,400}go\('sess'\)/.test(PANEL),
  'the desks card lives on the session page; there is no "desks" tab');

check('it scrolls the card into view',
  /function openDeskInPanel\(slot\)[\s\S]{0,400}scrollIntoView/.test(PANEL),
  'expanding something off-screen reads as a dead button');

check('an explicit browser escape hatch is still offered',
  /В браузере/.test(PANEL),
  'some things are genuinely better in a real tab');

// The 30s poll must not reload the chat mid-sentence.
check('loadDesks bails out while the chat is embedded',
  /if \(deskOpen === 'agent' && box\.querySelector\('iframe'\)\) return;/.test(PANEL),
  'refresh() runs every 30s and rewrites this card, which would reload the hub');

// ── 2. the shell keeps the tunnel inside the app ─────────────────────
console.log('\nshell: the tunnel host counts as ours');

check('isInternal() exists', /fun isInternal\(host: String\?\): Boolean/.test(SHELL));
check('the URL handler uses it', /if \(isInternal\(host\)\) return false/.test(SHELL));
check('trycloudflare is listed - this is where the agent hub lives',
  /"trycloudflare\.com"/.test(SHELL),
  'agent.yml opens a cloudflared quick tunnel, not ngrok');
check('ngrok is still listed for the older sessions',
  /"ngrok-free\.app"/.test(SHELL));
check('the suffix match is anchored at a label boundary',
  /h == it \|\| h\.endsWith\("\.\$it"\)/.test(SHELL),
  'a bare endsWith would also match evil-trycloudflare.com');
check('the host is lower-cased before matching',
  /host\?\.lowercase\(\)/.test(SHELL));

// ── 3. the routing itself, executed ──────────────────────────────────
// Mirrored from the Kotlin and asserted against it, so the copy cannot drift.
console.log('\nrouting behaviour');

const suffixes = [...SHELL.matchAll(/^\s*"([a-z0-9.-]+)",?\s*(?:\/\/.*)?$/gm)]
  .map(m => m[1])
  .filter(s => s.includes('.') && !s.endsWith('.kt'));
const PANEL_HOST = (SHELL.match(/const val PANEL_HOST = "([^"]+)"/) || [])[1];

check('PANEL_HOST was found in the source', !!PANEL_HOST, String(PANEL_HOST));
check('the suffix list was found', suffixes.length >= 5, JSON.stringify(suffixes));

const isInternal = host => {
  const h = (host || '').toLowerCase();
  if (!h) return false;
  if (h === PANEL_HOST) return true;
  return suffixes.some(s => h === s || h.endsWith('.' + s));
};

// Stays in the app.
for (const h of [
  PANEL_HOST,
  'abc-def-ghi.trycloudflare.com',
  'filly-above-terrapin.ngrok-free.app',
  'sj0404-collab.github.io',
  'ABC.TRYCLOUDFLARE.COM'
]) {
  check(`${h} opens in the app`, isInternal(h) === true);
}

// Goes to the browser.
for (const h of [
  'github.com',
  'api.github.com',
  'gofile.io',
  'store6.gofile.io'
]) {
  check(`${h} goes to the browser`, isInternal(h) === false);
}

// The lookalike the anchoring exists for.
check('evil-trycloudflare.com does NOT count as ours',
  isInternal('evil-trycloudflare.com') === false,
  'an unanchored endsWith would let a lookalike host load inside the app');
check('trycloudflare.com.attacker.net does NOT count as ours',
  isInternal('trycloudflare.com.attacker.net') === false);

// ── 4. the page still parses ─────────────────────────────────────────
console.log('\nthe panel still parses');
const script = PANEL.match(/<script>([\s\S]*)<\/script>/);
check('a script block was found', !!script);
if (script) {
  let ok = true, err = '';
  try { new vm.Script(script[1]); } catch (e) { ok = false; err = e.message; }
  check('it is syntactically valid JavaScript', ok, err);
}

console.log();
if (failed) { console.log(failed + ' check(s) failed'); process.exit(1); }
console.log('all checks passed');
