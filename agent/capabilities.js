'use strict';
// ═══════════════════════════════════════════════════════════════════
//  CAPABILITIES — agent-authored tools that run as REAL processes
// ═══════════════════════════════════════════════════════════════════
//
//  Why this exists next to custom_tool_* / plugin_*:
//
//  custom_tool_create and plugin_create store code that is executed inside a
//  vm context whose source is rejected outright when it mentions require,
//  process, child_process, import or eval. That sandbox is the right answer
//  for small pure helpers, and the wrong answer for everything the operator
//  actually asked for: talking to a device over adb, opening an RDP session,
//  driving Python, building a desktop program and screenshotting it. None of
//  those can be expressed without a real process.
//
//  A capability is therefore a directory on disk:
//
//    <workspace>/.zen-agent/capabilities/<name>/
//      manifest.json      name, description, runtime, deps, parameters
//      main.py|main.js|main.sh
//      artifacts/         files the capability produces (screenshots, reports)
//      logs/              stdout/stderr of background runs
//
//  It is spawned with a real interpreter. Arguments arrive as JSON in the
//  CAPABILITY_ARGS environment variable and on stdin, so any language can
//  read them. Anything written to artifacts/ is reported back by path, which
//  lets the model chain straight into image_info / ocr_image / vision_analyze
//  — that is how "make a program, then screenshot it" closes the loop.
//
//  Because this is genuine code execution, every mutating capability tool is
//  registered in WRITE_TOOLS by the host and is subject to the same approval
//  prompt as execute_command.

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn, spawnSync } = require('child_process');

const MAX_CODE_BYTES = 400 * 1024;
const MAX_OUTPUT_BYTES = 256 * 1024;
const DEFAULT_TIMEOUT_MS = 120000;
const MAX_TIMEOUT_MS = 3600000;

const IS_WINDOWS = process.platform === 'win32';

const RUNTIMES = {
  python: { file: 'main.py', probe: ['python3', 'python'], args: file => [file], label: 'Python 3' },
  node:   { file: 'main.js', probe: ['node'],               args: file => [file], label: 'Node.js' },
  bash:   { file: 'main.sh', probe: ['bash', 'sh'],         args: file => [file], label: 'Bash' },
  // PowerShell is the only runtime that is native on a Windows runner without
  // installing anything. pwsh (7.x) is preferred and present on GitHub's
  // windows images; powershell.exe (5.1) is the fallback on a plain desktop.
  powershell: {
    file: 'main.ps1',
    probe: ['pwsh', 'powershell'],
    args: file => ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', file],
    label: 'PowerShell'
  }
};

// ── helpers ────────────────────────────────────────────────────────

function safeName(value) {
  const name = String(value || '').trim();
  return /^[a-z][a-z0-9_]{2,48}$/i.test(name) ? name : null;
}

function which(binary) {
  const result = spawnSync(process.platform === 'win32' ? 'where' : 'which', [binary], { encoding: 'utf8' });
  return result.status === 0 ? String(result.stdout || '').split(/\r?\n/)[0].trim() : null;
}

function resolveInterpreter(runtime) {
  for (const candidate of RUNTIMES[runtime].probe) {
    const found = which(candidate);
    if (found) return found;
  }
  return null;
}

// A capability may declare system packages either as a flat list (one
// platform) or as {linux:[], windows:[]}. Everything downstream asks through
// this so a per-platform manifest never leaks an object where a list is due.
function systemPackagesFor(system, windows = IS_WINDOWS) {
  if (!system) return [];
  if (Array.isArray(system)) return system;
  return (windows ? system.windows : system.linux) || [];
}

function clip(text) {
  const value = String(text || '');
  if (Buffer.byteLength(value, 'utf8') <= MAX_OUTPUT_BYTES) return value;
  return value.slice(0, MAX_OUTPUT_BYTES) + `\n… output truncated at ${MAX_OUTPUT_BYTES} bytes`;
}

function walkFiles(root, base = root, acc = []) {
  let entries = [];
  try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch { return acc; }
  for (const entry of entries) {
    const full = path.join(root, entry.name);
    if (entry.isDirectory()) walkFiles(full, base, acc);
    else {
      let size = 0;
      try { size = fs.statSync(full).size; } catch {}
      acc.push({ path: full, relative: path.relative(base, full), bytes: size });
    }
  }
  return acc;
}

// ── storage ────────────────────────────────────────────────────────

function createStore(ctx) {
  const root = () => {
    const dir = path.join(ctx.workspaceRoot(), '.zen-agent', 'capabilities');
    fs.mkdirSync(dir, { recursive: true });
    return dir;
  };
  const dirFor = name => path.join(root(), name);
  const manifestFor = name => path.join(dirFor(name), 'manifest.json');

  const read = name => {
    try { return JSON.parse(fs.readFileSync(manifestFor(name), 'utf8')); }
    catch { return null; }
  };
  const write = manifest => {
    fs.mkdirSync(dirFor(manifest.name), { recursive: true });
    fs.writeFileSync(manifestFor(manifest.name), JSON.stringify(manifest, null, 2), 'utf8');
  };
  const list = () => {
    let names = [];
    try { names = fs.readdirSync(root(), { withFileTypes: true }).filter(x => x.isDirectory()).map(x => x.name); }
    catch { return []; }
    return names.map(read).filter(Boolean);
  };
  return { root, dirFor, manifestFor, read, write, list };
}

// ── blueprints ─────────────────────────────────────────────────────
//
//  Ready-made capabilities for the jobs that motivated this module. The model
//  can instantiate one with capability_create({name, template:'adb_bridge'})
//  and then edit it, which is far more reliable than asking a small free model
//  to write a correct adb or Xvfb wrapper from a blank page.

const BLUEPRINTS = {
  adb_bridge: {
    description: 'Подключиться к устройству по ADB (USB или Wi-Fi), выполнить команды, снять logcat и скриншот',
    runtime: 'python',
    system: ['android-tools-adb'],
    parameters: {
      host: 'IP[:порт] устройства для adb connect; пусто — использовать уже подключённое по USB',
      command: 'shell-команда на устройстве, например "dumpsys gfxinfo dev.legacy.eden_emulator.debug"',
      logcat_lines: 'сколько строк logcat забрать (0 — не забирать)',
      screenshot: 'true — снять скриншот экрана в artifacts/'
    },
    code: `import json, os, subprocess, sys, time

args = json.loads(os.environ.get("CAPABILITY_ARGS") or "{}")
ART = os.environ["CAPABILITY_ARTIFACTS"]

def adb(*a, timeout=120):
    p = subprocess.run(["adb", *a], capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout.strip(), p.stderr.strip()

report = {"steps": []}

host = str(args.get("host") or "").strip()
if host:
    if ":" not in host:
        host += ":5555"
    code, out, err = adb("connect", host)
    report["steps"].append({"connect": host, "exit": code, "out": out or err})

code, out, err = adb("devices", "-l")
report["devices"] = out
if "\\tdevice" not in out:
    report["error"] = ("Устройство не авторизовано или не подключено. "
                       "Для Wi-Fi: на телефоне включи отладку по Wi-Fi и передай host. "
                       "Для USB нужен физический доступ к машине агента.")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    sys.exit(1)

cmd = str(args.get("command") or "").strip()
if cmd:
    code, out, err = adb("shell", cmd)
    report["command"] = {"cmd": cmd, "exit": code, "stdout": out, "stderr": err}

lines = int(args.get("logcat_lines") or 0)
if lines > 0:
    code, out, err = adb("logcat", "-d", "-t", str(lines))
    dest = os.path.join(ART, "logcat.txt")
    with open(dest, "w", encoding="utf-8") as fh:
        fh.write(out)
    report["logcat"] = {"file": dest, "lines": out.count("\\n") + 1}

if args.get("screenshot"):
    remote = "/sdcard/_zen_shot.png"
    adb("shell", "screencap", "-p", remote)
    dest = os.path.join(ART, "screen-%d.png" % int(time.time()))
    adb("pull", remote, dest)
    adb("shell", "rm", "-f", remote)
    report["screenshot"] = dest

print(json.dumps(report, ensure_ascii=False, indent=2))
`
  },

  rdp_session: {
    description: 'Поднять RDP-сессию (xrdp + XFCE) на машине агента и вернуть данные для подключения',
    runtime: 'bash',
    platforms: ['linux'],
    system: ['xrdp', 'xfce4', 'xfce4-terminal'],
    parameters: {
      user: 'имя пользователя для входа (по умолчанию текущий)',
      password: 'пароль для RDP; если пусто — генерируется',
      port: 'порт xrdp, по умолчанию 3389'
    },
    code: `#!/usr/bin/env bash
# Bring up xrdp with an XFCE session and report the credentials.
set -uo pipefail

ARGS="\${CAPABILITY_ARGS:-{\\}}"
get() { printf '%s' "$ARGS" | python3 -c "import json,sys;print(json.load(sys.stdin).get('$1','') or '')"; }

USER_NAME="$(get user)"; [ -n "$USER_NAME" ] || USER_NAME="$(id -un)"
PASSWORD="$(get password)"; [ -n "$PASSWORD" ] || PASSWORD="zen$(openssl rand -hex 6)"
PORT="$(get port)"; [ -n "$PORT" ] || PORT=3389

echo "xfce4-session" > "$HOME/.xsession"
chmod +x "$HOME/.xsession"

if [ -f /etc/xrdp/xrdp.ini ]; then
  sudo -n sed -i "s/^port=.*/port=$PORT/" /etc/xrdp/xrdp.ini || true
  sudo -n systemctl restart xrdp 2>/dev/null || sudo -n /etc/init.d/xrdp restart 2>/dev/null || true
else
  echo "xrdp не установлен. Вызови capability_install для этой capability." >&2
  exit 1
fi

if [ -n "$PASSWORD" ]; then
  echo "$USER_NAME:$PASSWORD" | sudo -n chpasswd 2>/dev/null || \\
    echo "Не удалось задать пароль — нужен существующий." >&2
fi

sleep 2
STATE="$(ss -ltn 2>/dev/null | grep -c ":$PORT" || true)"

cat <<EOF
{
  "user": "$USER_NAME",
  "password": "$PASSWORD",
  "port": $PORT,
  "listening": $([ "$STATE" -gt 0 ] && echo true || echo false),
  "note": "Порт локальный для машины агента. Наружу — туннель TCP: ngrok tcp $PORT (на free-плане адрес случайный, статический домен работает только по HTTP)."
}
EOF
`
  },

  gui_screenshot: {
    description: 'Запустить GUI-программу на виртуальном экране Xvfb и снять скриншоты её работы',
    runtime: 'python',
    platforms: ['linux'],
    system: ['xvfb', 'x11-utils', 'imagemagick'],
    parameters: {
      command: 'команда запуска программы, например "python3 app.py"',
      seconds: 'сколько ждать прорисовки перед снимком (по умолчанию 4)',
      shots: 'сколько снимков сделать (по умолчанию 1)',
      interval: 'пауза между снимками в секундах (по умолчанию 2)',
      geometry: 'разрешение виртуального экрана, по умолчанию 1280x800x24'
    },
    code: `import json, os, signal, subprocess, sys, time

args = json.loads(os.environ.get("CAPABILITY_ARGS") or "{}")
ART = os.environ["CAPABILITY_ARTIFACTS"]
WORK = os.environ.get("CAPABILITY_CWD") or os.getcwd()

command = str(args.get("command") or "").strip()
if not command:
    print(json.dumps({"error": "Нужен параметр command."})); sys.exit(1)

geometry = str(args.get("geometry") or "1280x800x24")
wait = float(args.get("seconds") or 4)
shots = max(1, int(args.get("shots") or 1))
interval = float(args.get("interval") or 2)
display = ":%d" % (90 + (os.getpid() % 9))

env = dict(os.environ, DISPLAY=display)
xvfb = subprocess.Popen(["Xvfb", display, "-screen", "0", geometry, "-nolisten", "tcp"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(1.5)

app = subprocess.Popen(command, shell=True, cwd=WORK, env=env,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

report = {"display": display, "command": command, "screenshots": []}
try:
    time.sleep(wait)
    for index in range(shots):
        if index:
            time.sleep(interval)
        dest = os.path.join(ART, "shot-%02d.png" % (index + 1))
        grab = subprocess.run(["import", "-display", display, "-window", "root", dest],
                              capture_output=True, text=True)
        if grab.returncode == 0 and os.path.exists(dest):
            report["screenshots"].append({"file": dest, "bytes": os.path.getsize(dest)})
        else:
            report.setdefault("capture_errors", []).append(grab.stderr.strip())
    report["app_alive"] = app.poll() is None
finally:
    for proc in (app, xvfb):
        try:
            proc.terminate(); proc.wait(timeout=5)
        except Exception:
            try: proc.kill()
            except Exception: pass

out, err = "", ""
try: out, err = app.communicate(timeout=2)
except Exception: pass
report["app_stdout"] = (out or "")[-4000:]
report["app_stderr"] = (err or "")[-4000:]
report["hint"] = "Скриншоты — обычные PNG. Разбирай их через vision_analyze / ocr_image / image_info по указанным путям."
print(json.dumps(report, ensure_ascii=False, indent=2))
`
  },

  pytest_runner: {
    description: 'Прогнать тесты Python (pytest или unittest) и вернуть машинно-читаемый отчёт',
    runtime: 'python',
    pip: ['pytest'],
    parameters: {
      target: 'файл или папка с тестами (по умолчанию текущая рабочая папка)',
      framework: 'pytest или unittest (по умолчанию pytest)',
      extra: 'дополнительные аргументы одной строкой'
    },
    code: `import json, os, subprocess, sys

args = json.loads(os.environ.get("CAPABILITY_ARGS") or "{}")
ART = os.environ["CAPABILITY_ARTIFACTS"]
WORK = os.environ.get("CAPABILITY_CWD") or os.getcwd()

target = str(args.get("target") or ".")
framework = str(args.get("framework") or "pytest")
extra = str(args.get("extra") or "").split()

if framework == "unittest":
    cmd = [sys.executable, "-m", "unittest", "discover", "-v", "-s", target, *extra]
else:
    report_file = os.path.join(ART, "report.json")
    cmd = [sys.executable, "-m", "pytest", target, "-v", "--tb=short", *extra]

proc = subprocess.run(cmd, cwd=WORK, capture_output=True, text=True, timeout=1800)
log = os.path.join(ART, "tests.log")
with open(log, "w", encoding="utf-8") as fh:
    fh.write(proc.stdout + "\\n" + proc.stderr)

tail = (proc.stdout or "").strip().split("\\n")
summary = next((line for line in reversed(tail) if "passed" in line or "failed" in line or "OK" in line), "")

print(json.dumps({
    "command": " ".join(cmd),
    "exit": proc.returncode,
    "passed": proc.returncode == 0,
    "summary": summary.strip(),
    "log": log,
    "stdout_tail": "\\n".join(tail[-40:]),
    "stderr_tail": (proc.stderr or "")[-2000:]
}, ensure_ascii=False, indent=2))
`
  },

  http_probe: {
    description: 'Проверить HTTP-эндпоинт: код ответа, задержка, заголовки, фрагмент тела',
    runtime: 'python',
    parameters: {
      url: 'адрес для проверки',
      method: 'HTTP-метод, по умолчанию GET',
      times: 'сколько раз повторить для замера задержки (по умолчанию 3)'
    },
    code: `import json, os, time, urllib.request, urllib.error

args = json.loads(os.environ.get("CAPABILITY_ARGS") or "{}")
url = str(args.get("url") or "").strip()
if not url:
    print(json.dumps({"error": "Нужен url."})); raise SystemExit(1)

method = str(args.get("method") or "GET").upper()
times = max(1, int(args.get("times") or 3))
samples, status, headers, body = [], None, {}, ""

for _ in range(times):
    started = time.time()
    try:
        request = urllib.request.Request(url, method=method, headers={"User-Agent": "zen-capability"})
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = response.read(4096)
            status = response.status
            headers = dict(response.headers)
            body = payload.decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        status = exc.code
        body = exc.read(2048).decode("utf-8", "replace")
    except Exception as exc:
        samples.append(None)
        body = str(exc)
        continue
    samples.append(round((time.time() - started) * 1000))

good = [s for s in samples if s is not None]
print(json.dumps({
    "url": url, "method": method, "status": status,
    "latency_ms": good,
    "latency_avg_ms": round(sum(good) / len(good)) if good else None,
    "failures": samples.count(None),
    "headers": headers,
    "body_head": body[:1500]
}, ensure_ascii=False, indent=2))
`
  },

  // ── Windows ──────────────────────────────────────────────────────
  //
  //  A Windows runner is not Linux with different package names. RDP is a
  //  built-in service rather than xrdp, there is no Xvfb because the session
  //  already owns a desktop, and the shell is PowerShell. These four cover the
  //  same jobs as their Linux counterparts using what Windows actually has.

  windows_rdp: {
    description: 'Windows: включить встроенный RDP, завести пользователя и открыть правило брандмауэра',
    runtime: 'powershell',
    platforms: ['windows'],
    parameters: {
      user: 'имя пользователя RDP (по умолчанию zenrdp)',
      password: 'пароль; если пусто — генерируется',
      port: 'порт RDP, по умолчанию 3389'
    },
    code: `# Enable the RDP service Windows already ships with. No xrdp, no desktop
# install: the runner session is itself a full desktop.
$ErrorActionPreference = "Stop"
$args = $env:CAPABILITY_ARGS | ConvertFrom-Json

$user = if ($args.user) { [string]$args.user } else { "zenrdp" }
$port = if ($args.port) { [int]$args.port } else { 3389 }
$password = if ($args.password) { [string]$args.password } else {
  -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 14 | ForEach-Object { [char]$_ })
}

$report = [ordered]@{ user = $user; port = $port }

# Turn on Remote Desktop and let it through the firewall.
Set-ItemProperty -Path 'HKLM:\\System\\CurrentControlSet\\Control\\Terminal Server' \`
  -Name 'fDenyTSConnections' -Value 0
Set-ItemProperty -Path 'HKLM:\\System\\CurrentControlSet\\Control\\Terminal Server\\WinStations\\RDP-Tcp' \`
  -Name 'UserAuthentication' -Value 0
if ($port -ne 3389) {
  Set-ItemProperty -Path 'HKLM:\\System\\CurrentControlSet\\Control\\Terminal Server\\WinStations\\RDP-Tcp' \`
    -Name 'PortNumber' -Value $port
}
Enable-NetFirewallRule -DisplayGroup "Remote Desktop" -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName "zen-rdp-$port" -Direction Inbound -Protocol TCP \`
  -LocalPort $port -Action Allow -ErrorAction SilentlyContinue | Out-Null

# A local admin who is also allowed to log in remotely.
$secure = ConvertTo-SecureString $password -AsPlainText -Force
if (Get-LocalUser -Name $user -ErrorAction SilentlyContinue) {
  Set-LocalUser -Name $user -Password $secure
  $report.userExisted = $true
} else {
  New-LocalUser -Name $user -Password $secure -AccountNeverExpires -PasswordNeverExpires | Out-Null
  $report.userExisted = $false
}
Add-LocalGroupMember -Group "Administrators" -Member $user -ErrorAction SilentlyContinue
Add-LocalGroupMember -Group "Remote Desktop Users" -Member $user -ErrorAction SilentlyContinue

Restart-Service -Name TermService -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3

$listening = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -gt 0
$report.password = $password
$report.listening = $listening
$report.note = "Порт локальный для машины агента. Наружу: ngrok tcp $port (free-план даёт случайный адрес; статический домен работает только по HTTP)."

$report | ConvertTo-Json -Depth 4
`
  },

  windows_screenshot: {
    description: 'Windows: запустить программу и снять скриншоты её окна средствами .NET (без Xvfb)',
    runtime: 'powershell',
    platforms: ['windows'],
    parameters: {
      command: 'команда запуска, например "python app.py" или "notepad"',
      seconds: 'сколько ждать прорисовки перед снимком (по умолчанию 4)',
      shots: 'сколько снимков сделать (по умолчанию 1)',
      interval: 'пауза между снимками в секундах (по умолчанию 2)',
      keep_running: 'true — не закрывать программу после съёмки'
    },
    code: `# Windows already has a desktop, so screenshots come from System.Drawing
# against the real screen rather than a virtual X display.
$ErrorActionPreference = "Stop"
$a = $env:CAPABILITY_ARGS | ConvertFrom-Json
$art = $env:CAPABILITY_ARTIFACTS
$work = if ($env:CAPABILITY_CWD) { $env:CAPABILITY_CWD } else { (Get-Location).Path }

if (-not $a.command) { @{ error = "Нужен параметр command." } | ConvertTo-Json; exit 1 }

$wait     = if ($a.seconds)  { [double]$a.seconds }  else { 4 }
$shots    = if ($a.shots)    { [int]$a.shots }       else { 1 }
$interval = if ($a.interval) { [double]$a.interval } else { 2 }

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

$outLog = Join-Path $art "app-stdout.txt"
$errLog = Join-Path $art "app-stderr.txt"
$proc = Start-Process -FilePath "powershell" \`
  -ArgumentList @("-NoProfile", "-Command", [string]$a.command) \`
  -WorkingDirectory $work -PassThru \`
  -RedirectStandardOutput $outLog -RedirectStandardError $errLog

$report = [ordered]@{ command = [string]$a.command; pid = $proc.Id; screenshots = @() }
Start-Sleep -Seconds $wait

for ($i = 1; $i -le $shots; $i++) {
  if ($i -gt 1) { Start-Sleep -Seconds $interval }
  $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
  $bmp = New-Object System.Drawing.Bitmap $bounds.Width, $bounds.Height
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $gfx.CopyFromScreen($bounds.Location, [System.Drawing.Point]::Empty, $bounds.Size)
  $dest = Join-Path $art ("shot-{0:D2}.png" -f $i)
  $bmp.Save($dest, [System.Drawing.Imaging.ImageFormat]::Png)
  $gfx.Dispose(); $bmp.Dispose()
  $report.screenshots += @{ file = $dest; bytes = (Get-Item $dest).Length }
}

$report.app_alive = -not $proc.HasExited
if (-not $a.keep_running -and -not $proc.HasExited) {
  Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
}
if (Test-Path $outLog) { $report.app_stdout = (Get-Content $outLog -Raw -ErrorAction SilentlyContinue) }
if (Test-Path $errLog) { $report.app_stderr = (Get-Content $errLog -Raw -ErrorAction SilentlyContinue) }
$report.hint = "Скриншоты — обычные PNG. Разбирай их через vision_analyze / ocr_image / image_info по указанным путям."

$report | ConvertTo-Json -Depth 5
`
  },

  windows_system: {
    description: 'Windows: инвентарь машины — ОС, CPU, память, диски, GPU, службы, открытые порты',
    runtime: 'powershell',
    platforms: ['windows'],
    parameters: {
      services: 'true — включить список работающих служб',
      ports: 'true — включить прослушиваемые порты'
    },
    code: `$ErrorActionPreference = "SilentlyContinue"
$a = $env:CAPABILITY_ARGS | ConvertFrom-Json

$os  = Get-CimInstance Win32_OperatingSystem
$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1

$report = [ordered]@{
  os = @{
    caption = $os.Caption
    version = $os.Version
    build   = $os.BuildNumber
    arch    = $os.OSArchitecture
    uptime_hours = [math]::Round(((Get-Date) - $os.LastBootUpTime).TotalHours, 2)
  }
  cpu = @{
    name    = $cpu.Name
    cores   = $cpu.NumberOfCores
    threads = $cpu.NumberOfLogicalProcessors
  }
  memory = @{
    total_mb = [math]::Round($os.TotalVisibleMemorySize / 1KB)
    free_mb  = [math]::Round($os.FreePhysicalMemory / 1KB)
  }
  disks = @(Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object {
    @{ drive = $_.DeviceID
       total_gb = [math]::Round($_.Size / 1GB, 1)
       free_gb  = [math]::Round($_.FreeSpace / 1GB, 1) }
  })
  gpu = @(Get-CimInstance Win32_VideoController | ForEach-Object {
    @{ name = $_.Name; driver = $_.DriverVersion; ram_mb = [math]::Round($_.AdapterRAM / 1MB) }
  })
  powershell = $PSVersionTable.PSVersion.ToString()
}

if ($a.services) {
  $report.services = @(Get-Service | Where-Object { $_.Status -eq 'Running' } |
    Select-Object -First 60 | ForEach-Object { $_.Name })
}
if ($a.ports) {
  $report.listening_ports = @(Get-NetTCPConnection -State Listen |
    Select-Object -ExpandProperty LocalPort -Unique | Sort-Object)
}

$report | ConvertTo-Json -Depth 5
`
  },

  windows_adb: {
    description: 'Windows: ADB через platform-tools — подключение, команды, logcat, скриншот устройства',
    runtime: 'powershell',
    platforms: ['windows'],
    system: { windows: ['adb'], linux: ['android-tools-adb'] },
    parameters: {
      host: 'IP[:порт] для adb connect; пусто — уже подключённое по USB',
      command: 'shell-команда на устройстве',
      logcat_lines: 'сколько строк logcat забрать (0 — не забирать)',
      screenshot: 'true — снять скриншот экрана устройства'
    },
    code: `$ErrorActionPreference = "Continue"
$a = $env:CAPABILITY_ARGS | ConvertFrom-Json
$art = $env:CAPABILITY_ARTIFACTS

# choco puts adb on PATH; a manual platform-tools unpack usually does not.
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  foreach ($candidate in @(
      "$env:LOCALAPPDATA\\Android\\Sdk\\platform-tools\\adb.exe",
      "$env:ProgramData\\chocolatey\\bin\\adb.exe",
      "C:\\platform-tools\\adb.exe")) {
    if (Test-Path $candidate) { $adb = $candidate; break }
  }
}
if (-not $adb) {
  @{ error = "adb не найден. Вызови capability_install для этой capability (choco install adb)." } |
    ConvertTo-Json; exit 1
}

$report = [ordered]@{ adb = $adb; steps = @() }

if ($a.host) {
  $target = [string]$a.host
  if ($target -notmatch ":") { $target = "\${target}:5555" }
  $out = & $adb connect $target 2>&1 | Out-String
  $report.steps += @{ connect = $target; out = $out.Trim() }
}

$devices = & $adb devices -l 2>&1 | Out-String
$report.devices = $devices.Trim()
if ($devices -notmatch "device\\s*$" -and $devices -notmatch "\`tdevice") {
  $report.error = "Устройство не авторизовано или не подключено. Для Wi-Fi включи отладку по Wi-Fi и передай host."
  $report | ConvertTo-Json -Depth 5
  exit 1
}

if ($a.command) {
  $out = & $adb shell ([string]$a.command) 2>&1 | Out-String
  $report.command = @{ cmd = [string]$a.command; stdout = $out.Trim() }
}

if ($a.logcat_lines -and [int]$a.logcat_lines -gt 0) {
  $out = & $adb logcat -d -t ([string][int]$a.logcat_lines) 2>&1 | Out-String
  $dest = Join-Path $art "logcat.txt"
  $out | Out-File -FilePath $dest -Encoding utf8
  $report.logcat = @{ file = $dest; lines = ($out -split "\`n").Count }
}

if ($a.screenshot) {
  $remote = "/sdcard/_zen_shot.png"
  & $adb shell screencap -p $remote | Out-Null
  $dest = Join-Path $art ("screen-{0}.png" -f ([int][double]::Parse((Get-Date -UFormat %s))))
  & $adb pull $remote $dest | Out-Null
  & $adb shell rm -f $remote | Out-Null
  if (Test-Path $dest) { $report.screenshot = $dest }
}

$report | ConvertTo-Json -Depth 5
`
  }
};

// ── factory ────────────────────────────────────────────────────────

function createCapabilities(ctx) {
  const store = createStore(ctx);
  const audit = (event, data) => { try { ctx.auditEvent?.(event, data); } catch {} };

  function ensureLayout(name) {
    const dir = store.dirFor(name);
    fs.mkdirSync(path.join(dir, 'artifacts'), { recursive: true });
    fs.mkdirSync(path.join(dir, 'logs'), { recursive: true });
    return dir;
  }

  function runtimeState(manifest) {
    const interpreter = resolveInterpreter(manifest.runtime);
    const missingSystem = systemPackagesFor(manifest.system).filter(pkg => !which(pkg.replace(/^.*-/, '')) && !which(pkg));
    return { interpreter, interpreterFound: !!interpreter, missingSystem };
  }

  function describe(manifest) {
    const state = runtimeState(manifest);
    return {
      name: manifest.name,
      description: manifest.description,
      runtime: manifest.runtime,
      parameters: manifest.parameters || {},
      template: manifest.template || null,
      background: !!manifest.background,
      directory: store.dirFor(manifest.name),
      dependencies: { pip: manifest.pip || [], npm: manifest.npm || [], system: systemPackagesFor(manifest.system), systemDeclared: manifest.system || [] },
      installed: !!manifest.installedAt,
      interpreterFound: state.interpreterFound,
      lastRun: manifest.lastRun || null,
      createdAt: manifest.createdAt,
      updatedAt: manifest.updatedAt
    };
  }

  // ── list / templates ────────────────────────────────────────────

  function capabilityList() {
    const items = store.list().map(describe);
    return {
      directory: store.root(),
      count: items.length,
      capabilities: items,
      templates: Object.entries(BLUEPRINTS).map(([id, bp]) => ({ id, runtime: bp.runtime, description: bp.description })),
      hint: items.length
        ? 'Запуск: capability_run({name, args}). Зависимости: capability_install({name}).'
        : 'Пусто. Быстрый старт: capability_create({name:"adb", template:"adb_bridge"}).'
    };
  }

  function capabilityTemplates(args = {}) {
    const id = String(args.template || args.name || '').trim();
    if (id) {
      const bp = BLUEPRINTS[id];
      if (!bp) return { error: `Шаблон '${id}' не найден. Доступны: ${Object.keys(BLUEPRINTS).join(', ')}` };
      return { template: id, ...bp };
    }
    const here = IS_WINDOWS ? 'windows' : 'linux';
    const all = Object.entries(BLUEPRINTS).map(([key, bp]) => ({
      id: key, runtime: bp.runtime, description: bp.description,
      parameters: bp.parameters || {},
      platforms: bp.platforms || ['linux', 'windows'],
      dependencies: { pip: bp.pip || [], npm: bp.npm || [], system: systemPackagesFor(bp.system) }
    }));
    // Default to what actually runs here; the rest is still listed separately
    // so the model can see it exists without being tempted to use it.
    const usable = all.filter(t => t.platforms.includes(here));
    return {
      platform: here,
      templates: args.all ? all : usable,
      otherPlatform: args.all ? [] : all.filter(t => !t.platforms.includes(here)).map(t => t.id),
      usage: `capability_create({name:"мое_имя", template:"${here === 'windows' ? 'windows_screenshot' : 'gui_screenshot'}"}) создаёт capability из шаблона; code можно не передавать. Передай all:true, чтобы увидеть шаблоны других платформ.`
    };
  }

  // ── create / inspect / delete ───────────────────────────────────

  function capabilityCreate(args = {}) {
    const name = safeName(args.name);
    if (!name) return { error: 'Имя capability: 3–49 символов, латиница/цифры/_, начинается с буквы.' };

    const templateId = String(args.template || '').trim();
    const blueprint = templateId ? BLUEPRINTS[templateId] : null;
    if (templateId && !blueprint) {
      return { error: `Шаблон '${templateId}' не найден. Доступны: ${Object.keys(BLUEPRINTS).join(', ')}` };
    }

    const runtime = String(args.runtime || blueprint?.runtime || 'python').toLowerCase();
    if (!RUNTIMES[runtime]) return { error: `runtime должен быть одним из: ${Object.keys(RUNTIMES).join(', ')}` };

    const code = String(args.code || blueprint?.code || '');
    if (!code.trim()) return { error: 'Нужен code либо template.' };
    if (Buffer.byteLength(code, 'utf8') > MAX_CODE_BYTES) return { error: `Код больше ${MAX_CODE_BYTES} байт.` };

    const description = String(args.description || blueprint?.description || '').trim();
    if (!description) return { error: 'Нужно description — по нему модель выбирает инструмент.' };

    const existing = store.read(name);
    if (existing && !args.overwrite) {
      return { error: `Capability '${name}' уже существует. Передай overwrite:true для замены.` };
    }

    const dir = ensureLayout(name);
    const entry = RUNTIMES[runtime].file;
    const codePath = path.join(dir, entry);

    const manifest = {
      name,
      description,
      runtime,
      entry,
      template: templateId || existing?.template || null,
      parameters: args.parameters && typeof args.parameters === 'object' ? args.parameters : (blueprint?.parameters || {}),
      pip: Array.isArray(args.pip) ? args.pip : (blueprint?.pip || []),
      npm: Array.isArray(args.npm) ? args.npm : (blueprint?.npm || []),
      system: (Array.isArray(args.system) || (args.system && typeof args.system === 'object')) ? args.system : (blueprint?.system || []),
      timeoutMs: Math.min(MAX_TIMEOUT_MS, Math.max(1000, Number(args.timeout_ms || args.timeout * 1000 || DEFAULT_TIMEOUT_MS))),
      background: !!args.background,
      createdAt: existing?.createdAt || new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      installedAt: existing?.installedAt || null
    };

    try {
      fs.writeFileSync(codePath, code, 'utf8');
      if (runtime === 'bash') fs.chmodSync(codePath, 0o755);
      store.write(manifest);
    } catch (e) {
      return { error: 'Не удалось сохранить capability: ' + e.message };
    }

    audit('capability_created', { name, runtime, template: templateId || null });
    const state = runtimeState(manifest);
    const needsInstall = manifest.pip.length || manifest.npm.length || systemPackagesFor(manifest.system).length;

    // A Linux blueprint on Windows (or the reverse) fails deep inside the
    // script with a confusing error. Say so at creation time instead.
    const here = IS_WINDOWS ? 'windows' : 'linux';
    const supported = blueprint?.platforms || manifest.platforms || ['linux', 'windows'];
    const platformWarning = supported.includes(here)
      ? null
      : `Шаблон '${templateId}' рассчитан на ${supported.join('/')}, а агент работает на ${here}. ` +
        (here === 'windows'
          ? 'Для Windows возьми windows_rdp / windows_screenshot / windows_adb / windows_system.'
          : 'Для Linux возьми rdp_session / gui_screenshot / adb_bridge.');

    return {
      success: true,
      capability: describe(manifest),
      file: codePath,
      interpreter: state.interpreter || `НЕ НАЙДЕН (${RUNTIMES[runtime].label})`,
      platform: here,
      ...(platformWarning ? { platformWarning } : {}),
      nextStep: needsInstall
        ? `Зависимости не установлены. Вызови capability_install({name:"${name}"}), затем capability_run.`
        : `Готово к запуску: capability_run({name:"${name}", args:{...}}).`
    };
  }

  function capabilityInspect(args = {}) {
    const name = safeName(args.name);
    if (!name) return { error: 'Укажи name.' };
    const manifest = store.read(name);
    if (!manifest) return { error: `Capability '${name}' не найдена.` };
    const dir = store.dirFor(name);
    let code = '';
    try { code = fs.readFileSync(path.join(dir, manifest.entry), 'utf8'); }
    catch (e) { code = '(не удалось прочитать: ' + e.message + ')'; }
    return {
      ...describe(manifest),
      code,
      artifacts: walkFiles(path.join(dir, 'artifacts')).map(f => ({ path: f.path, bytes: f.bytes })),
      runtimeState: runtimeState(manifest)
    };
  }

  function capabilityDelete(args = {}) {
    const name = safeName(args.name);
    if (!name) return { error: 'Укажи name.' };
    if (!store.read(name)) return { error: `Capability '${name}' не найдена.` };
    try { fs.rmSync(store.dirFor(name), { recursive: true, force: true }); }
    catch (e) { return { error: 'Не удалось удалить: ' + e.message }; }
    audit('capability_deleted', { name });
    return { success: true, name };
  }

  // ── dependency install ──────────────────────────────────────────

  function runInstall(command, args, cwd) {
    const result = spawnSync(command, args, {
      cwd, env: ctx.commandEnvironment(), encoding: 'utf8', timeout: 900000, maxBuffer: 32 * 1024 * 1024
    });
    return {
      command: `${command} ${args.join(' ')}`,
      exit: result.status === null ? 1 : result.status,
      stdout: clip(result.stdout),
      stderr: clip(result.stderr || (result.error ? result.error.message : ''))
    };
  }

  function capabilityInstall(args = {}) {
    const name = safeName(args.name);
    if (!name) return { error: 'Укажи name.' };
    const manifest = store.read(name);
    if (!manifest) return { error: `Capability '${name}' не найдена.` };

    const dir = store.dirFor(name);
    const steps = [];

    if (systemPackagesFor(manifest.system).length || (!Array.isArray(manifest.system) && manifest.system)) {
      // System packages are named per platform. A manifest may either give a
      // plain list (Linux names) or an object {linux:[], windows:[]}, so one
      // capability can declare both and stay portable.
      const systemPackages = Array.isArray(manifest.system)
        ? manifest.system
        : (IS_WINDOWS ? (manifest.system.windows || []) : (manifest.system.linux || []));

      if (!systemPackages.length) {
        steps.push({
          command: 'system packages', exit: 0, stdout: '',
          stderr: `Для ${IS_WINDOWS ? 'Windows' : 'Linux'} системные пакеты не объявлены — пропущено.`
        });
      } else if (IS_WINDOWS) {
        const choco = which('choco');
        if (choco) {
          steps.push(runInstall(choco, ['install', '-y', '--no-progress', '--limit-output', ...systemPackages], dir));
        } else {
          const winget = which('winget');
          if (winget) {
            for (const pkg of systemPackages) {
              steps.push(runInstall(winget, ['install', '--silent', '--accept-package-agreements',
                '--accept-source-agreements', '-e', '--id', pkg], dir));
            }
          } else {
            steps.push({ command: 'choco', exit: 1, stdout: '', stderr: 'Ни choco, ни winget не найдены — системные пакеты поставить нельзя.' });
          }
        }
      } else if (which('sudo') || process.getuid?.() === 0) {
        const prefix = process.getuid?.() === 0 ? [] : ['-n'];
        const update = runInstall(process.getuid?.() === 0 ? 'apt-get' : 'sudo',
          process.getuid?.() === 0 ? ['update', '-qq'] : [...prefix, 'apt-get', 'update', '-qq'], dir);
        steps.push(update);
        const install = runInstall(process.getuid?.() === 0 ? 'apt-get' : 'sudo',
          process.getuid?.() === 0
            ? ['install', '-y', '-qq', ...systemPackages]
            : [...prefix, 'apt-get', 'install', '-y', '-qq', ...systemPackages], dir);
        steps.push(install);
      } else {
        steps.push({ command: 'apt-get', exit: 1, stdout: '', stderr: 'sudo недоступен — системные пакеты поставить нельзя.' });
      }
    }

    if (manifest.pip.length) {
      const python = resolveInterpreter('python');
      if (python) steps.push(runInstall(python, ['-m', 'pip', 'install', '--quiet', '--user', ...manifest.pip], dir));
      else steps.push({ command: 'pip', exit: 1, stdout: '', stderr: 'python3 не найден.' });
    }

    if (manifest.npm.length) {
      const npm = which('npm');
      if (npm) steps.push(runInstall(npm, ['install', '--prefix', dir, '--no-audit', '--no-fund', ...manifest.npm], dir));
      else steps.push({ command: 'npm', exit: 1, stdout: '', stderr: 'npm не найден.' });
    }

    const ok = steps.every(step => step.exit === 0);
    if (ok) {
      manifest.installedAt = new Date().toISOString();
      manifest.updatedAt = manifest.installedAt;
      store.write(manifest);
    }
    audit('capability_install', { name, ok });
    return {
      success: ok,
      name,
      steps,
      message: steps.length
        ? (ok ? 'Зависимости установлены.' : 'Часть шагов упала — смотри stderr каждого шага.')
        : 'У этой capability нет объявленных зависимостей.'
    };
  }

  // ── run ─────────────────────────────────────────────────────────

  function backgroundRegistryPath() { return path.join(store.root(), 'background.json'); }
  function readBackground() {
    try { return JSON.parse(fs.readFileSync(backgroundRegistryPath(), 'utf8')) || {}; } catch { return {}; }
  }
  function writeBackground(data) {
    try { fs.writeFileSync(backgroundRegistryPath(), JSON.stringify(data, null, 2), 'utf8'); } catch {}
  }
  function alive(pid) {
    if (!pid) return false;
    try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
  }

  async function capabilityRun(args = {}) {
    const name = safeName(args.name);
    if (!name) return { error: 'Укажи name.' };
    const manifest = store.read(name);
    if (!manifest) return { error: `Capability '${name}' не найдена. Сначала capability_list или capability_create.` };

    const dir = ensureLayout(name);
    const entryPath = path.join(dir, manifest.entry);
    if (!fs.existsSync(entryPath)) return { error: `Файл capability пропал: ${entryPath}` };

    const interpreter = resolveInterpreter(manifest.runtime);
    if (!interpreter) {
      return { error: `Интерпретатор ${RUNTIMES[manifest.runtime].label} не найден на этой машине.` };
    }

    const callArgs = (args.args && typeof args.args === 'object') ? args.args
      : (args.tool_args && typeof args.tool_args === 'object') ? args.tool_args : {};

    let cwd = ctx.workspaceRoot();
    if (args.cwd) {
      const resolved = ctx.resolvePath ? ctx.resolvePath(args.cwd, 'cwd') : { path: path.resolve(cwd, String(args.cwd)) };
      if (resolved.error) return resolved;
      cwd = resolved.path;
    }

    const artifactsDir = path.join(dir, 'artifacts');
    const before = new Set(walkFiles(artifactsDir).map(f => f.path));

    const env = {
      ...ctx.commandEnvironment(),
      CAPABILITY_ARGS: JSON.stringify(callArgs),
      CAPABILITY_NAME: name,
      CAPABILITY_DIR: dir,
      CAPABILITY_ARTIFACTS: artifactsDir,
      CAPABILITY_CWD: cwd,
      NODE_PATH: path.join(dir, 'node_modules'),
      PYTHONUNBUFFERED: '1'
    };

    const spawnArgs = RUNTIMES[manifest.runtime].args(entryPath);
    const background = args.background !== undefined ? !!args.background : !!manifest.background;

    if (background) {
      const logPath = path.join(dir, 'logs', `${Date.now()}.log`);
      const fd = fs.openSync(logPath, 'a');
      const child = spawn(interpreter, spawnArgs, {
        cwd, env, detached: process.platform !== 'win32', stdio: ['ignore', fd, fd]
      });
      child.unref();
      try { fs.closeSync(fd); } catch {}
      const registry = readBackground();
      registry[name] = { name, pid: child.pid, logPath, startedAt: new Date().toISOString(), args: callArgs };
      writeBackground(registry);
      audit('capability_run', { name, background: true, pid: child.pid });
      return {
        success: true, name, background: true, pid: child.pid, logPath,
        message: 'Capability запущена в фоне. Логи: capability_logs({name}). Остановить: capability_stop({name}).'
      };
    }

    const timeoutMs = Math.min(MAX_TIMEOUT_MS,
      Math.max(1000, Number(args.timeout_ms || (args.timeout ? args.timeout * 1000 : 0) || manifest.timeoutMs || DEFAULT_TIMEOUT_MS)));
    const started = Date.now();

    const result = await new Promise(resolve => {
      let stdout = '', stderr = '', settled = false, timedOut = false;
      let child;
      const finish = (exit, error = '') => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        resolve({ stdout, stderr: error ? (stderr + '\n' + error) : stderr, exit, timedOut });
      };
      const timer = setTimeout(() => {
        timedOut = true;
        try { process.platform === 'win32' ? child?.kill() : process.kill(-child.pid, 'SIGKILL'); }
        catch { try { child?.kill('SIGKILL'); } catch {} }
        finish(124, `Capability остановлена по таймауту ${timeoutMs} мс.`);
      }, timeoutMs);
      timer.unref?.();

      try {
        child = spawn(interpreter, spawnArgs, {
          cwd, env, detached: process.platform !== 'win32', stdio: ['pipe', 'pipe', 'pipe']
        });
      } catch (e) { finish(1, 'spawn: ' + e.message); return; }

      child.stdout.on('data', chunk => { stdout = clip(stdout + chunk.toString('utf8')); });
      child.stderr.on('data', chunk => { stderr = clip(stderr + chunk.toString('utf8')); });
      child.on('error', err => finish(1, err.message));
      child.on('close', code => finish(typeof code === 'number' ? code : 1));

      try {
        child.stdin.write(JSON.stringify(callArgs));
        child.stdin.end();
      } catch {}
    });

    const durationMs = Date.now() - started;
    const artifacts = walkFiles(artifactsDir)
      .filter(file => !before.has(file.path))
      .map(file => ({ path: file.path, relative: file.relative, bytes: file.bytes }));

    // A capability may end its stdout with a JSON object; surface it parsed so
    // the model reads fields instead of re-parsing text.
    let parsed = null;
    const trimmed = result.stdout.trim();
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
      try { parsed = JSON.parse(trimmed); } catch {}
    }

    manifest.lastRun = { at: new Date().toISOString(), exit: result.exit, durationMs };
    store.write(manifest);
    audit('capability_run', { name, exit: result.exit, durationMs });

    const images = artifacts.filter(a => /\.(png|jpe?g|webp|bmp)$/i.test(a.path));
    return {
      success: result.exit === 0,
      name,
      runtime: manifest.runtime,
      exit: result.exit,
      timedOut: result.timedOut,
      durationMs,
      cwd,
      args: callArgs,
      result: parsed,
      stdout: parsed ? undefined : result.stdout || '(пусто)',
      stderr: result.stderr || undefined,
      artifacts,
      ...(images.length ? { imageHint: 'Скриншоты можно разобрать: vision_analyze({path}) или ocr_image({path}).' } : {})
    };
  }

  function capabilityLogs(args = {}) {
    const name = safeName(args.name);
    if (!name) return { error: 'Укажи name.' };
    if (!store.read(name)) return { error: `Capability '${name}' не найдена.` };
    const entry = readBackground()[name];
    const dir = store.dirFor(name);
    let logPath = entry?.logPath;
    if (!logPath || !fs.existsSync(logPath)) {
      const logs = walkFiles(path.join(dir, 'logs')).sort((a, b) => a.path.localeCompare(b.path));
      logPath = logs.length ? logs[logs.length - 1].path : null;
    }
    if (!logPath) return { error: `У '${name}' нет логов — фоновых запусков не было.` };
    const limit = Math.max(1, Math.min(2000, Number(args.limit || 200)));
    let text = '';
    try { text = fs.readFileSync(logPath, 'utf8'); } catch (e) { return { error: e.message }; }
    const lines = text.split('\n');
    return {
      name, logPath,
      running: entry ? alive(entry.pid) : false,
      pid: entry?.pid || null,
      startedAt: entry?.startedAt || null,
      shown: Math.min(limit, lines.length),
      totalLines: lines.length,
      output: clip(lines.slice(-limit).join('\n'))
    };
  }

  function capabilityStop(args = {}) {
    const name = safeName(args.name);
    if (!name) return { error: 'Укажи name.' };
    const registry = readBackground();
    const entry = registry[name];
    if (!entry) return { error: `Фоновый запуск '${name}' не зарегистрирован.` };
    if (!alive(entry.pid)) {
      delete registry[name]; writeBackground(registry);
      return { success: true, name, message: 'Процесс уже завершился.' };
    }
    try {
      if (process.platform === 'win32') process.kill(entry.pid);
      else process.kill(-entry.pid, 'SIGTERM');
    } catch {
      try { process.kill(entry.pid, 'SIGTERM'); } catch (e) { return { error: 'Не удалось остановить: ' + e.message }; }
    }
    delete registry[name]; writeBackground(registry);
    audit('capability_stop', { name, pid: entry.pid });
    return { success: true, name, pid: entry.pid, logPath: entry.logPath };
  }

  // ── dispatch ────────────────────────────────────────────────────

  const HANDLERS = {
    capability_list: () => capabilityList(),
    capability_templates: a => capabilityTemplates(a),
    capability_create: a => capabilityCreate(a),
    capability_inspect: a => capabilityInspect(a),
    capability_delete: a => capabilityDelete(a),
    capability_install: a => capabilityInstall(a),
    capability_run: a => capabilityRun(a),
    capability_logs: a => capabilityLogs(a),
    capability_stop: a => capabilityStop(a)
  };

  return {
    handles: name => Object.prototype.hasOwnProperty.call(HANDLERS, name),
    handle: (name, args) => HANDLERS[name](args || {}),
    blueprints: BLUEPRINTS
  };
}

const CAPABILITY_TOOLS = {
  capability_list: 'Показать capabilities — само-созданные инструменты, работающие как настоящие процессы',
  capability_templates: 'Готовые шаблоны capability: adb, RDP, скриншоты GUI, тесты, HTTP-проба',
  capability_create: 'Создать capability на Python/Node/Bash с реальным доступом к системе (adb, RDP, GUI)',
  capability_inspect: 'Показать код, параметры и артефакты capability',
  capability_run: 'Запустить capability и получить результат, stdout и созданные файлы',
  capability_install: 'Установить зависимости capability (pip/npm/apt)',
  capability_logs: 'Прочитать логи фоновой capability',
  capability_stop: 'Остановить фоновую capability',
  capability_delete: 'Удалить capability'
};

const CAPABILITY_REQUIRED_ARGS = {
  capability_create: ['name'],
  capability_inspect: ['name'],
  capability_run: ['name'],
  capability_install: ['name'],
  capability_logs: ['name'],
  capability_stop: ['name'],
  capability_delete: ['name']
};

const CAPABILITY_WRITE_TOOLS = [
  'capability_create', 'capability_run', 'capability_install', 'capability_stop', 'capability_delete'
];

const CAPABILITY_ICONS = {
  capability_list: '🧰', capability_templates: '📐', capability_create: '🧱', capability_inspect: '🔎',
  capability_run: '⚡', capability_install: '📦', capability_logs: '📜', capability_stop: '⏹️', capability_delete: '🗑️'
};

const CAPABILITY_PROMPT = `САМОРАСШИРЕНИЕ ЧЕРЕЗ CAPABILITIES (реальные процессы):
- custom_tool_* и plugin_* исполняются в песочнице vm: там ЗАПРЕЩЕНЫ require, process, child_process, import, eval. Они годятся только для чистых вычислений над текстом и файлами.
- Всё, что требует настоящей системы — adb, RDP, запуск GUI-программы, скриншоты, pip-пакеты, длинные фоновые задачи — делается через capability_*. Capability это папка с manifest.json и main.py/main.js/main.sh, которая запускается настоящим интерпретатором.
- Порядок: capability_templates() → capability_create({name, template}) → capability_install({name}) если есть зависимости → capability_run({name, args:{...}}).
- Свой код вместо шаблона: capability_create({name, description, runtime:"python"|"node"|"bash"|"powershell", code, parameters, pip:[], npm:[], system:[]}).
- ПЛАТФОРМА. capability_templates() показывает только то, что работает на текущей ОС; передай all:true, чтобы увидеть остальное. На Windows runtime по умолчанию — powershell (pwsh есть всегда, bash и Xvfb нет). Windows-шаблоны: windows_rdp (встроенный RDP, не xrdp), windows_screenshot (снимок реального экрана через System.Drawing, Xvfb не нужен), windows_adb, windows_system. Linux-шаблоны rdp_session и gui_screenshot на Windows не работают и наоборот.
- Системные пакеты называются по-разному: передавай system:{linux:["android-tools-adb"], windows:["adb"]} — capability_install сам выберет apt-get, choco или winget.
- Внутри capability аргументы приходят JSON-ом в переменной окружения CAPABILITY_ARGS и на stdin. Пиши файлы в папку из CAPABILITY_ARTIFACTS — они вернутся в ответе списком путей.
- Если последняя строка stdout — JSON-объект, он вернётся уже разобранным в поле result. Пользуйся этим вместо печати свободного текста.
- Скриншоты и любые изображения из artifacts разбирай через vision_analyze / ocr_image / image_info по возвращённому пути — так замыкается цикл «сделал программу → запустил → посмотрел на результат».
- Долгие задачи: capability_run({name, background:true}), затем capability_logs({name}) и capability_stop({name}).
- Шаблоны: adb_bridge (устройство по USB/Wi-Fi, logcat, скриншот экрана), rdp_session (xrdp+XFCE на машине агента), gui_screenshot (Xvfb + запуск программы + PNG), pytest_runner (тесты), http_probe (проверка эндпоинта).`;

module.exports = {
  createCapabilities,
  CAPABILITY_TOOLS,
  CAPABILITY_REQUIRED_ARGS,
  CAPABILITY_WRITE_TOOLS,
  CAPABILITY_ICONS,
  CAPABILITY_PROMPT,
  BLUEPRINTS
};
