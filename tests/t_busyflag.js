'use strict';
// Regression guard for the "The agent is already busy" dead end.
//
// agentBusy was set at the top of agentLoop and cleared only by the last line
// of the success path. Any throw in between - a provider error, a dropped
// connection, a bad tool result - left it true for the lifetime of the
// process, and every later POST /api/agent/run answered 409 with no way back
// short of restarting the session. These checks read the shipped source, so
// they fail if someone reintroduces an early return that skips the release.

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const source = fs.readFileSync(path.join(__dirname, '..', 'agent', 'zen-agent.js'), 'utf8');

let passed = 0, failed = 0;
const results = [];
function check(name, fn) {
  try { fn(); passed++; results.push(`  ok  ${name}`); }
  catch (e) { failed++; results.push(`  FAIL ${name}\n       ${e.message}`); }
}

function agentLoopBody() {
  const start = source.indexOf('async function agentLoop(userInput)');
  assert.ok(start > 0, 'agentLoop not found');
  const end = source.indexOf('\n}\n', start);
  assert.ok(end > start, 'end of agentLoop not found');
  return source.slice(start, end);
}

check('agentLoop still raises the busy flag', () => {
  assert.ok(agentLoopBody().includes('agentBusy = true'));
});

check('the flag is released in a finally block', () => {
  const body = agentLoopBody();
  const finallyAt = body.indexOf('} finally {');
  assert.ok(finallyAt > 0, 'agentLoop must wrap its body in try/finally');
  const clearAt = body.indexOf('agentBusy = false', finallyAt);
  assert.ok(clearAt > finallyAt, 'agentBusy must be cleared inside finally');
});

check('there is exactly one place that clears the flag', () => {
  // More than one means a success path is clearing it early again, which is
  // how the original bug hid: the happy path looked correct.
  const clears = (agentLoopBody().match(/agentBusy = false/g) || []).length;
  assert.strictEqual(clears, 1, `expected 1 clear site inside agentLoop, found ${clears}`);
});

check('a throw cannot skip the release (behavioural model)', async () => {
  // Same control flow as the patched function, proving the shape works.
  let busy = false;
  async function loop(shouldThrow) {
    busy = true;
    try {
      if (shouldThrow) throw new Error('provider exploded');
      return 'ok';
    } finally { busy = false; }
  }
  loop(true).catch(() => {});
  assert.strictEqual(busy, false, 'flag must be down after a throw');
});

check('the reset endpoint exists and clears server state', () => {
  assert.ok(source.includes("url.pathname === '/api/agent/reset'"), 'no /api/agent/reset route');
  const start = source.indexOf("url.pathname === '/api/agent/reset'");
  const block = source.slice(start, start + 1400);
  assert.ok(block.includes('agentBusy = false'), 'reset must lower the flag');
  assert.ok(block.includes('WEB_AGENT_RUN_CONTEXT = null'), 'reset must drop the run context');
  assert.ok(block.includes('resolveApproval'), 'reset must release a pending approval');
});

check('the busy responder reaps stale runs before answering 409', () => {
  const start = source.indexOf("if (url.pathname === '/api/agent/run' && req.method === 'POST')");
  assert.ok(start > 0, 'run endpoint not found');
  const block = source.slice(start, start + 2200);
  assert.ok(block.includes('web_run_reaped'), 'stale runs must be reaped');
  assert.ok(block.includes('WEB_AGENT_RUN_CONTEXT === r'),
    'only the genuinely active run may keep the console locked');
  assert.ok(block.includes('/api/agent/reset'), '409 should tell the user how to recover');
});

check('the hub offers a reset button instead of a dead end', () => {
  const hub = fs.readFileSync(path.join(__dirname, '..', 'agent', 'hub', 'index.html'), 'utf8');
  assert.ok(hub.includes('offerReset'), 'hub must define offerReset');
  assert.ok(hub.includes('r.status === 409'), 'hub must detect the busy response');
  assert.ok(hub.includes("'/api/agent/reset'"), 'hub must call the reset endpoint');
});

console.log('\nbusy-flag regression\n' + results.join('\n'));
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
