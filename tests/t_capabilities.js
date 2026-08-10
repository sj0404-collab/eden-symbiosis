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

// A PowerShell one-liner that parses a file and exits non-zero on a syntax
// error. PSParser.Tokenize was the obvious choice and is useless here: it
// leaves $Error empty and reports success on a plainly broken script, so an
// earlier version of this test passed no matter what. Parser::ParseFile fills
// an error collection, which is the thing worth checking.
function psParseScript(file) {
  const quoted = file.replace(/'/g, "''");
  return `$e=$null; [System.Management.Automation.Language.Parser]::ParseFile('${quoted}',[ref]$null,[ref]$e) | Out-Null;`
    + ` if ($e.Count) { $e | ForEach-Object { Write-Host $_.Message }; exit 1 }; exit 0`;
}

// First of the given binaries that exists on PATH.
function which0(candidates) {
  const { spawnSync } = require('child_process');
  for (const binary of candidates) {
    const probe = spawnSync(process.platform === 'win32' ? 'where' : 'which', [binary], { encoding: 'utf8' });
    if (probe.status === 0) return binary;
  }
  return candidates[0];
}

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
    const entryFor = { python: 'main.py', node: 'main.js', bash: 'main.sh', powershell: 'main.ps1' };
    const pwsh = ['pwsh', 'powershell'].map(bin => spawnSync(bin, ['-Version'], { encoding: 'utf8' }))
      .some(r => r.status === 0);
    let checked = 0, skippedPwsh = 0;

    for (const [id, bp] of Object.entries(BLUEPRINTS)) {
      const create = await caps.handle('capability_create', { name: `tpl_${id}`, template: id });
      assert.ok(create.success, `${id}: ${create.error}`);
      const file = path.join(create.capability.directory, entryFor[create.capability.runtime]);
      assert.ok(fs.existsSync(file), `${id}: entry file missing at ${file}`);

      let probe;
      if (bp.runtime === 'python') probe = spawnSync('python3', ['-m', 'py_compile', file], { encoding: 'utf8' });
      else if (bp.runtime === 'node') probe = spawnSync('node', ['--check', file], { encoding: 'utf8' });
      else if (bp.runtime === 'bash') probe = spawnSync('bash', ['-n', file], { encoding: 'utf8' });
      else {
        // PowerShell blueprints are parsed by PowerShell itself when it is
        // available. On a Linux runner without pwsh the check is skipped
        // rather than faked - the Windows job covers it for real.
        if (!pwsh) { skippedPwsh++; continue; }
        probe = spawnSync(which0(['pwsh', 'powershell']), ['-NoProfile', '-Command', psParseScript(file)], { encoding: 'utf8' });
        // spawnSync merges nothing: surface the parser's own message on failure.
        if (probe.status !== 0) probe.stderr = (probe.stdout || '') + (probe.stderr || '');
      }
      assert.strictEqual(probe.status, 0, `${id} has a syntax error:\n${probe.stderr}`);
      checked++;
    }
    assert.ok(checked >= 5, 'expected the cross-platform blueprints to be checked');
    if (skippedPwsh) results.push(`       note: ${skippedPwsh} PowerShell blueprint(s) not parsed - pwsh absent`);
  });

  await check('the PowerShell syntax check actually rejects a broken script', () => {
    const { spawnSync } = require('child_process');
    const bin = which0(['pwsh', 'powershell']);
    if (spawnSync(bin, ['-Version'], { encoding: 'utf8' }).status !== 0) {
      results.push('       note: skipped - pwsh absent');
      return;
    }
    const broken = path.join(workspace, 'broken.ps1');
    fs.writeFileSync(broken, '$x = @{ unclosed = "hello\nif ($x -eq { ) { Write-Host "nope"\n');
    const bad = spawnSync(bin, ['-NoProfile', '-Command', psParseScript(broken)], { encoding: 'utf8' });
    assert.strictEqual(bad.status, 1, 'a broken script must fail the check');

    const fine = path.join(workspace, 'fine.ps1');
    fs.writeFileSync(fine, 'Write-Host "ok"\n');
    const good = spawnSync(bin, ['-NoProfile', '-Command', psParseScript(fine)], { encoding: 'utf8' });
    assert.strictEqual(good.status, 0, 'a valid script must pass');
  });

  await check('windows blueprints are declared windows-only and use PowerShell', () => {
    for (const id of ['windows_rdp', 'windows_screenshot', 'windows_system', 'windows_adb']) {
      const bp = BLUEPRINTS[id];
      assert.ok(bp, `${id} missing`);
      assert.strictEqual(bp.runtime, 'powershell', `${id} should use PowerShell`);
      assert.deepStrictEqual(bp.platforms, ['windows'], `${id} should be windows-only`);
    }
    assert.deepStrictEqual(BLUEPRINTS.rdp_session.platforms, ['linux']);
    assert.deepStrictEqual(BLUEPRINTS.gui_screenshot.platforms, ['linux']);
  });

  await check('templates are filtered to the running platform', async () => {
    const listed = await caps.handle('capability_templates');
    const ids = listed.templates.map(t => t.id);
    const windowsOnly = ['windows_rdp', 'windows_screenshot', 'windows_system', 'windows_adb'];
    if (process.platform === 'win32') {
      assert.ok(ids.includes('windows_rdp'), 'windows templates must be offered on Windows');
      assert.ok(!ids.includes('gui_screenshot'), 'Xvfb template must not be offered on Windows');
    } else {
      assert.ok(ids.includes('gui_screenshot'));
      for (const id of windowsOnly) assert.ok(!ids.includes(id), `${id} must be hidden on Linux`);
      assert.ok(listed.otherPlatform.includes('windows_rdp'), 'hidden ones should still be named');
    }
    const everything = await caps.handle('capability_templates', { all: true });
    assert.strictEqual(everything.templates.length, Object.keys(BLUEPRINTS).length);
  });

  await check('creating an off-platform template warns instead of failing silently', async () => {
    const offPlatform = process.platform === 'win32' ? 'gui_screenshot' : 'windows_rdp';
    const r = await caps.handle('capability_create', { name: 'off_plat', template: offPlatform });
    assert.ok(r.success, 'creation should still succeed');
    assert.ok(r.platformWarning, 'a platform mismatch must be reported');
    assert.ok(/windows|linux/i.test(r.platformWarning));
  });

  await check('per-platform system packages resolve to the right list', async () => {
    const r = await caps.handle('capability_create', {
      name: 'dual_deps', description: 'per-platform deps', runtime: 'python',
      code: 'print("{}")', system: { linux: ['android-tools-adb'], windows: ['adb'] }
    });
    assert.ok(r.success, JSON.stringify(r));
    const expected = process.platform === 'win32' ? 'adb' : 'android-tools-adb';
    assert.deepStrictEqual(r.capability.dependencies.system, [expected]);
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
