'use strict';
// Tests for the capability layer. These run the real thing: a capability is
// created on disk, spawned with a real interpreter, and its artifacts are
// checked. No mocking - the whole point of capabilities is that they are not
// sandboxed, so a test that stubs the process proves nothing.

const assert = require('assert');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { createCapabilities, CAPABILITY_TOOLS, CAPABILITY_WRITE_TOOLS, BLUEPRINTS } = require('../agent/capabilities.js');

let passed = 0, failed = 0;
const results = [];

function check(name, fn) {
  return Promise.resolve()
    .then(fn)
    .then(() => { passed++; results.push(`  ok  ${name}`); })
    .catch(e => { failed++; results.push(`  FAIL ${name}\n       ${e.message}`); });
}

const workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'zen-cap-'));
const caps = createCapabilities({
  workspaceRoot: () => workspace,
  commandEnvironment: () => ({ ...process.env }),
  resolvePath: input => ({ path: path.resolve(workspace, String(input)) }),
  auditEvent: () => {}
});

(async () => {
  // ── registration ────────────────────────────────────────────────
  await check('all nine capability tools are exported', () => {
    assert.strictEqual(Object.keys(CAPABILITY_TOOLS).length, 9);
    for (const name of Object.keys(CAPABILITY_TOOLS)) assert.ok(caps.handles(name), `${name} not dispatched`);
  });

  await check('mutating tools are declared write tools', () => {
    for (const name of ['capability_create', 'capability_run', 'capability_install', 'capability_delete']) {
      assert.ok(CAPABILITY_WRITE_TOOLS.includes(name), `${name} must require approval`);
    }
    assert.ok(!CAPABILITY_WRITE_TOOLS.includes('capability_list'), 'listing must stay read-only');
  });

  await check('unknown tool names are not claimed', () => {
    assert.ok(!caps.handles('read_file'));
    assert.ok(!caps.handles('capability_nonsense'));
  });

  // ── validation ──────────────────────────────────────────────────
  await check('bad names are rejected', async () => {
    for (const bad of ['', 'a', '9lives', 'has space', 'dash-name', '../escape']) {
      const r = await caps.handle('capability_create', { name: bad, description: 'x', code: 'print(1)' });
      assert.ok(r.error, `name '${bad}' should be rejected`);
    }
  });

  await check('create requires code or template', async () => {
    const r = await caps.handle('capability_create', { name: 'empty_one', description: 'x' });
    assert.ok(r.error && /code|template/i.test(r.error));
  });

  await check('unknown runtime is rejected', async () => {
    const r = await caps.handle('capability_create', { name: 'weird_rt', description: 'x', code: 'x', runtime: 'perl' });
    assert.ok(r.error && /runtime/i.test(r.error));
  });

  await check('unknown template is rejected with a list of real ones', async () => {
    const r = await caps.handle('capability_create', { name: 'nope_tpl', template: 'does_not_exist' });
    assert.ok(r.error && r.error.includes('adb_bridge'));
  });

  // ── real execution ──────────────────────────────────────────────
  await check('python capability runs and returns parsed JSON', async () => {
    const create = await caps.handle('capability_create', {
      name: 'echo_sum',
      description: 'adds two numbers',
      runtime: 'python',
      code: [
        'import json, os',
        'a = json.loads(os.environ.get("CAPABILITY_ARGS") or "{}")',
        'print(json.dumps({"sum": int(a.get("x", 0)) + int(a.get("y", 0))}))'
      ].join('\n')
    });
    assert.ok(create.success, 'create failed: ' + JSON.stringify(create));

    const run = await caps.handle('capability_run', { name: 'echo_sum', args: { x: 20, y: 22 } });
    assert.ok(run.success, 'run failed: ' + JSON.stringify(run));
    assert.strictEqual(run.exit, 0);
    assert.ok(run.result, 'stdout JSON should be parsed into result');
    assert.strictEqual(run.result.sum, 42);
  });

  await check('capability writes artifacts and they are reported back', async () => {
    await caps.handle('capability_create', {
      name: 'make_file',
      description: 'writes an artifact',
      runtime: 'python',
      code: [
        'import json, os',
        'dest = os.path.join(os.environ["CAPABILITY_ARTIFACTS"], "note.txt")',
        'open(dest, "w").write("hello from a real process")',
        'print(json.dumps({"wrote": dest}))'
      ].join('\n')
    });
    const run = await caps.handle('capability_run', { name: 'make_file' });
    assert.ok(run.success, JSON.stringify(run));
    assert.strictEqual(run.artifacts.length, 1);
    assert.strictEqual(path.basename(run.artifacts[0].path), 'note.txt');
    assert.ok(fs.readFileSync(run.artifacts[0].path, 'utf8').includes('real process'));
  });

  await check('capability really escapes the vm sandbox (spawns a process)', async () => {
    // This is the whole justification for the module: code that custom_tool
    // would refuse to even store must run here.
    await caps.handle('capability_create', {
      name: 'uses_require',
      description: 'uses require and child_process, which custom_tool forbids',
      runtime: 'node',
      code: [
        'const { execSync } = require("child_process");',
        'const out = execSync("echo spawned").toString().trim();',
        'console.log(JSON.stringify({ out, pid: process.pid }));'
      ].join('\n')
    });
    const run = await caps.handle('capability_run', { name: 'uses_require' });
    assert.ok(run.success, JSON.stringify(run));
    assert.strictEqual(run.result.out, 'spawned');
    assert.ok(run.result.pid > 0 && run.result.pid !== process.pid, 'must be a separate process');
  });

  await check('non-zero exit is reported as failure, not silently swallowed', async () => {
    await caps.handle('capability_create', {
      name: 'fails_hard',
      description: 'exits non-zero',
      runtime: 'bash',
      code: 'echo "to stderr" >&2\nexit 3\n'
    });
    const run = await caps.handle('capability_run', { name: 'fails_hard' });
    assert.strictEqual(run.success, false);
    assert.strictEqual(run.exit, 3);
    assert.ok((run.stderr || '').includes('to stderr'));
  });

  await check('timeout kills a runaway capability', async () => {
    await caps.handle('capability_create', {
      name: 'runs_forever',
      description: 'sleeps far too long',
      runtime: 'bash',
      code: 'sleep 60\n'
    });
    const started = Date.now();
    const run = await caps.handle('capability_run', { name: 'runs_forever', timeout_ms: 2000 });
    const elapsed = Date.now() - started;
    assert.strictEqual(run.timedOut, true, 'should report timeout');
    assert.ok(elapsed < 20000, `should stop promptly, took ${elapsed}ms`);
  });

  await check('arguments also arrive on stdin for languages without env parsing', async () => {
    await caps.handle('capability_create', {
      name: 'reads_stdin',
      description: 'reads args from stdin',
      runtime: 'python',
      code: [
        'import json, sys',
        'data = json.loads(sys.stdin.read() or "{}")',
        'print(json.dumps({"got": data.get("ping")}))'
      ].join('\n')
    });
    const run = await caps.handle('capability_run', { name: 'reads_stdin', args: { ping: 'pong' } });
    assert.ok(run.success, JSON.stringify(run));
    assert.strictEqual(run.result.got, 'pong');
  });

  // ── background ──────────────────────────────────────────────────
  await check('background run reports a pid and collects logs', async () => {
    await caps.handle('capability_create', {
      name: 'slow_worker',
      description: 'prints then sleeps',
      runtime: 'bash',
      code: 'echo "worker started"\nsleep 30\n'
    });
    const run = await caps.handle('capability_run', { name: 'slow_worker', background: true });
    assert.ok(run.success && run.pid > 0, JSON.stringify(run));

    // Poll for the log line instead of sleeping blindly.
    let text = '';
    for (let i = 0; i < 40 && !text.includes('worker started'); i++) {
      await new Promise(r => setTimeout(r, 100));
      const logs = await caps.handle('capability_logs', { name: 'slow_worker' });
      text = logs.output || '';
    }
    assert.ok(text.includes('worker started'), 'background stdout must reach the log');

    const stop = await caps.handle('capability_stop', { name: 'slow_worker' });
    assert.ok(stop.success, JSON.stringify(stop));
  });

  // ── templates ───────────────────────────────────────────────────
  await check('every blueprint is syntactically valid for its runtime', async () => {
    const { spawnSync } = require('child_process');
    for (const [id, bp] of Object.entries(BLUEPRINTS)) {
      const create = await caps.handle('capability_create', { name: `tpl_${id}`, template: id });
      assert.ok(create.success, `${id}: ${create.error}`);
      const file = path.join(create.capability.directory, create.capability.runtime === 'python' ? 'main.py'
        : create.capability.runtime === 'node' ? 'main.js' : 'main.sh');
      let probe;
      if (bp.runtime === 'python') probe = spawnSync('python3', ['-m', 'py_compile', file], { encoding: 'utf8' });
      else if (bp.runtime === 'node') probe = spawnSync('node', ['--check', file], { encoding: 'utf8' });
      else probe = spawnSync('bash', ['-n', file], { encoding: 'utf8' });
      assert.strictEqual(probe.status, 0, `${id} has a syntax error:\n${probe.stderr}`);
    }
  });

  await check('templates advertise their dependencies', () => {
    assert.ok(BLUEPRINTS.adb_bridge.system.includes('android-tools-adb'));
    assert.ok(BLUEPRINTS.gui_screenshot.system.includes('xvfb'));
    assert.ok(BLUEPRINTS.rdp_session.system.includes('xrdp'));
  });

  await check('http_probe template actually works against a local server', async () => {
    const http = require('http');
    const server = http.createServer((req, res) => { res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end('pong'); });
    await new Promise(r => server.listen(0, '127.0.0.1', r));
    const port = server.address().port;
    try {
      await caps.handle('capability_create', { name: 'probe_it', template: 'http_probe', overwrite: true });
      const run = await caps.handle('capability_run', { name: 'probe_it', args: { url: `http://127.0.0.1:${port}/`, times: 2 } });
      assert.ok(run.success, JSON.stringify(run));
      assert.strictEqual(run.result.status, 200);
      assert.strictEqual(run.result.body_head, 'pong');
      assert.strictEqual(run.result.failures, 0);
    } finally { server.close(); }
  });

  // ── lifecycle ───────────────────────────────────────────────────
  await check('overwrite is required to replace an existing capability', async () => {
    const again = await caps.handle('capability_create', { name: 'echo_sum', description: 'x', code: 'print(1)' });
    assert.ok(again.error && /overwrite/i.test(again.error));
    const forced = await caps.handle('capability_create', { name: 'echo_sum', description: 'x', code: 'print(1)', overwrite: true });
    assert.ok(forced.success);
  });

  await check('list, inspect and delete agree with each other', async () => {
    const list = await caps.handle('capability_list');
    assert.ok(list.count >= 5, 'expected the created capabilities');
    assert.ok(list.templates.length === Object.keys(BLUEPRINTS).length);

    const inspect = await caps.handle('capability_inspect', { name: 'make_file' });
    assert.ok(inspect.code.includes('CAPABILITY_ARTIFACTS'));

    const del = await caps.handle('capability_delete', { name: 'make_file' });
    assert.ok(del.success);
    const after = await caps.handle('capability_inspect', { name: 'make_file' });
    assert.ok(after.error, 'deleted capability must be gone');
  });

  await check('running a missing capability fails clearly', async () => {
    const run = await caps.handle('capability_run', { name: 'never_made' });
    assert.ok(run.error && /не найдена/i.test(run.error));
  });

  // ── report ──────────────────────────────────────────────────────
  console.log('\ncapabilities\n' + results.join('\n'));
  console.log(`\n${passed} passed, ${failed} failed`);
  try { fs.rmSync(workspace, { recursive: true, force: true }); } catch {}
  process.exit(failed ? 1 : 0);
})();
