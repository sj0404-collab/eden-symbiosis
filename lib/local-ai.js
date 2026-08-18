// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Local models: download a GGUF from Hugging Face into the runner, serve it
// with llama.cpp, and talk to it over the OpenAI-compatible API llama-server
// already speaks.
//
// WHY THIS EXISTS
//   Every online provider here has a limit. Zen's free tier runs out, GitHub
//   Models is rate-limited, OpenRouter's free ids are throttled and a paid key
//   costs money. A model running inside the runner has none of that: it is on
//   the same machine as the agent, and the only budget is the six hours the
//   job lives for. That is the point of the feature - "если я не захочу
//   использовать онлайн аи с лимитами".
//
// WHY GGUF + llama.cpp
//   A GitHub runner has no GPU and 16 GB of RAM. llama.cpp is the one runtime
//   that is fast enough on plain CPU, ships a static binary with no CUDA and
//   no Python, and exposes /v1/chat/completions - so callCompatibleProvider
//   style code works unchanged. Transformers would need torch: gigabytes of
//   download before the first token.
//
// WHY THE MODELS ARE SMALL
//   The catalogue tops out around 5 GB. The runner has ~14 GB of usable disk
//   and the download happens while the user waits, so a 30 GB model is not a
//   real option even though the RAM would hold it. Sizes below are the actual
//   quantised file sizes, not parameter counts.
//
// The module is optional: zen-agent.js falls back to a stub when it cannot be
// required, so a missing dependency degrades to "local AI unavailable" instead
// of breaking the agent.

'use strict';
const fs = require('fs');
const os = require('os');
const path = require('path');
const https = require('https');
const http = require('http');
const { spawn, execFileSync } = require('child_process');

/**
 * Curated GGUF models, smallest first.
 *
 * Each entry names a repo and a specific quantised file. Q4_K_M throughout:
 * it is the usual quality/size compromise, and on a CPU-only runner the
 * difference against Q8 is far smaller than the extra download.
 */
const CATALOG = [
  {
    id: 'qwen2.5-1.5b-instruct',
    name: 'Qwen 2.5 1.5B Instruct',
    repo: 'Qwen/Qwen2.5-1.5B-Instruct-GGUF',
    file: 'qwen2.5-1.5b-instruct-q4_k_m.gguf',
    sizeMb: 1120,
    ctx: 32768,
    note: 'Самая быстрая. Годится для коротких задач и правок.'
  },
  {
    id: 'qwen2.5-coder-3b',
    name: 'Qwen 2.5 Coder 3B',
    repo: 'Qwen/Qwen2.5-Coder-3B-Instruct-GGUF',
    file: 'qwen2.5-coder-3b-instruct-q4_k_m.gguf',
    sizeMb: 2100,
    ctx: 32768,
    note: 'Заточена под код. Разумный выбор по умолчанию.'
  },
  {
    id: 'llama-3.2-3b-instruct',
    name: 'Llama 3.2 3B Instruct',
    repo: 'bartowski/Llama-3.2-3B-Instruct-GGUF',
    file: 'Llama-3.2-3B-Instruct-Q4_K_M.gguf',
    sizeMb: 2020,
    ctx: 131072,
    note: 'Хорошо держит длинный контекст.'
  },
  {
    id: 'qwen2.5-coder-7b',
    name: 'Qwen 2.5 Coder 7B',
    repo: 'Qwen/Qwen2.5-Coder-7B-Instruct-GGUF',
    file: 'qwen2.5-coder-7b-instruct-q4_k_m.gguf',
    sizeMb: 4680,
    ctx: 32768,
    note: 'Самая сильная из списка. Дольше грузится, медленнее отвечает.'
  },
  {
    id: 'mistral-7b-instruct',
    name: 'Mistral 7B Instruct v0.3',
    repo: 'bartowski/Mistral-7B-Instruct-v0.3-GGUF',
    file: 'Mistral-7B-Instruct-v0.3-Q4_K_M.gguf',
    sizeMb: 4370,
    ctx: 32768,
    note: 'Универсальная, без уклона в код.'
  }
];

/** Where llama.cpp comes from when it is not already installed. */
const LLAMA_RELEASE =
  'https://github.com/ggml-org/llama.cpp/releases/latest/download/llama-bin-ubuntu-x64.zip';

function hfToken() {
  return process.env.HF_TOKEN || process.env.HUGGINGFACE_TOKEN || '';
}

class LocalAiManager {
  constructor(options = {}) {
    this.storageRoot = options.storageRoot || (() => path.join(os.homedir(), '.zen-local-ai'));
    this.logger = options.logger || console;
    this.tasks = new Map();
    this.server = null;      // the running llama-server child
    this.serving = null;     // { modelId, port, startedAt }
    this.config = { activeEngine: 'llamacpp', selectedModel: '', engines: { llamacpp: { model: '' } } };
    this._loadConfig();
  }

  // ── paths ──────────────────────────────────────────────────────────
  root() {
    const dir = typeof this.storageRoot === 'function' ? this.storageRoot() : this.storageRoot;
    const target = path.join(dir || path.join(os.homedir(), '.zen-local-ai'), 'models');
    try { fs.mkdirSync(target, { recursive: true }); } catch {}
    return target;
  }

  runtimeRoot() {
    const dir = typeof this.storageRoot === 'function' ? this.storageRoot() : this.storageRoot;
    const target = path.join(dir || path.join(os.homedir(), '.zen-local-ai'), 'runtime');
    try { fs.mkdirSync(target, { recursive: true }); } catch {}
    return target;
  }

  _configPath() { return path.join(path.dirname(this.root()), 'config.json'); }

  _loadConfig() {
    try {
      const saved = JSON.parse(fs.readFileSync(this._configPath(), 'utf8'));
      if (saved && typeof saved === 'object') Object.assign(this.config, saved);
    } catch {}
  }

  _saveConfig() {
    try { fs.writeFileSync(this._configPath(), JSON.stringify(this.config, null, 2)); } catch {}
  }

  // ── catalogue and inventory ────────────────────────────────────────
  catalog() {
    const have = new Set(this.listModels().map(m => m.id));
    return CATALOG.map(m => ({ ...m, downloaded: have.has(m.id) }));
  }

  /**
   * Models actually present on disk.
   *
   * Only fully downloaded files count. A partial download is left as
   * <file>.part precisely so that an interrupted job cannot advertise a
   * truncated model that would fail at load time.
   */
  listModels() {
    const dir = this.root();
    let entries = [];
    try { entries = fs.readdirSync(dir); } catch { return []; }
    return entries
      .filter(f => f.endsWith('.gguf'))
      .map(f => {
        const known = CATALOG.find(m => m.file === f);
        const full = path.join(dir, f);
        let sizeMb = 0;
        try { sizeMb = Math.round(fs.statSync(full).size / 1048576); } catch {}
        return {
          id: known ? known.id : f.replace(/\.gguf$/, ''),
          name: known ? known.name : f,
          file: f, path: full, sizeMb,
          ctx: known ? known.ctx : 8192,
          engine: 'llamacpp',
          serving: !!(this.serving && this.serving.file === f)
        };
      });
  }

  // ── runtime ────────────────────────────────────────────────────────
  _binary() {
    // A system-wide llama-server wins: the runner may already have one, and
    // downloading 30 MB again for every session is waste.
    for (const candidate of ['llama-server', 'llama-cpp-server']) {
      try {
        execFileSync(process.platform === 'win32' ? 'where' : 'which', [candidate],
          { stdio: 'ignore', timeout: 2500 });
        return candidate;
      } catch {}
    }
    const local = path.join(this.runtimeRoot(), 'llama-server');
    return fs.existsSync(local) ? local : null;
  }

  listRuntimes() {
    const bin = this._binary();
    return [{
      id: 'llamacpp',
      name: 'llama.cpp server',
      installed: !!bin,
      path: bin || null,
      note: bin ? 'готов' : 'не установлен — скачается при первом запуске модели'
    }];
  }

  async runtimeCatalog() {
    return [{ id: 'llamacpp', name: 'llama.cpp server', url: LLAMA_RELEASE, sizeMb: 30 }];
  }

  installRuntimeToTermux() {
    return { success: false, error: 'На Termux собери llama.cpp через pkg install llama-cpp.' };
  }

  async startRuntimeDownload() {
    const id = 'runtime_' + Date.now().toString(36);
    const task = { id, kind: 'runtime', status: 'running', percent: 0, message: 'Скачиваю llama.cpp…' };
    this.tasks.set(id, task);
    this._installRuntime(task).catch(e => {
      task.status = 'error';
      task.message = String(e && e.message || e);
    });
    return task;
  }

  async _installRuntime(task) {
    const dest = this.runtimeRoot();
    const zip = path.join(dest, 'llama.zip');
    await this._download(LLAMA_RELEASE, zip, p => { task.percent = Math.round(p * 90); });
    task.message = 'Распаковываю…';
    try {
      execFileSync('unzip', ['-o', '-j', zip, '*/llama-server', 'llama-server', '-d', dest],
        { stdio: 'ignore', timeout: 120000 });
    } catch {
      execFileSync('unzip', ['-o', '-j', zip, '-d', dest], { stdio: 'ignore', timeout: 120000 });
    }
    try { fs.chmodSync(path.join(dest, 'llama-server'), 0o755); } catch {}
    try { fs.unlinkSync(zip); } catch {}
    task.status = 'done'; task.percent = 100; task.message = 'llama.cpp готов';
  }

  // ── downloading a model ────────────────────────────────────────────
  startDownload(body = {}) {
    const wanted = String(body.modelId || body.id || '').trim();
    const entry = CATALOG.find(m => m.id === wanted);
    if (!entry) {
      return { id: null, status: 'error', message: `Неизвестная модель '${wanted}'. Список: /api/local-ai/catalog` };
    }
    const id = 'download_' + Date.now().toString(36);
    const task = {
      id, kind: 'model', modelId: entry.id, status: 'running', percent: 0,
      totalMb: entry.sizeMb, message: `Скачиваю ${entry.name}…`
    };
    this.tasks.set(id, task);
    this._downloadModel(entry, task).catch(e => {
      task.status = 'error';
      task.message = String(e && e.message || e);
    });
    return task;
  }

  async _downloadModel(entry, task) {
    const target = path.join(this.root(), entry.file);
    if (fs.existsSync(target)) {
      task.status = 'done'; task.percent = 100; task.message = 'Уже скачана';
      return;
    }
    // Written to .part first: a job cancelled mid-download must not leave a
    // truncated .gguf that listModels() would then offer as usable.
    const part = target + '.part';
    const url = `https://huggingface.co/${entry.repo}/resolve/main/${entry.file}?download=true`;
    await this._download(url, part, p => {
      task.percent = Math.round(p * 100);
      task.message = `${entry.name}: ${task.percent}%`;
    });
    fs.renameSync(part, target);
    task.status = 'done'; task.percent = 100; task.message = `${entry.name} скачана`;
    this.config.selectedModel = entry.id;
    this.config.engines.llamacpp.model = entry.file;
    this._saveConfig();
  }

  /** GET with redirects; HF serves models from a CDN and always redirects. */
  _download(url, dest, onProgress, depth = 0) {
    if (depth > 5) return Promise.reject(new Error('Слишком много редиректов'));
    return new Promise((resolve, reject) => {
      const headers = { 'User-Agent': 'symbiosis-local-ai' };
      // The token is only needed for gated repos, but sending it also lifts
      // the anonymous rate limit that otherwise fails a large download.
      if (hfToken() && url.includes('huggingface.co')) headers.Authorization = `Bearer ${hfToken()}`;
      const mod = url.startsWith('http://') ? http : https;
      const req = mod.get(url, { headers, timeout: 60000 }, res => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          res.resume();
          const next = new URL(res.headers.location, url).toString();
          this._download(next, dest, onProgress, depth + 1).then(resolve, reject);
          return;
        }
        if (res.statusCode !== 200) {
          res.resume();
          const hint = res.statusCode === 401 || res.statusCode === 403
            ? ' — репозиторий закрыт, нужен HF_TOKEN с доступом к нему'
            : '';
          reject(new Error(`HTTP ${res.statusCode} при скачивании${hint}`));
          return;
        }
        const total = parseInt(res.headers['content-length'] || '0', 10);
        let got = 0;
        const out = fs.createWriteStream(dest);
        res.on('data', chunk => {
          got += chunk.length;
          if (total && onProgress) onProgress(Math.min(1, got / total));
        });
        res.pipe(out);
        out.on('finish', () => out.close(() => resolve()));
        out.on('error', reject);
      });
      req.on('error', reject);
      req.on('timeout', () => req.destroy(new Error('Таймаут скачивания')));
    });
  }

  task(id) { return this.tasks.get(id) || null; }

  async remove(body = {}) {
    const entry = this.listModels().find(m => m.id === body.modelId || m.file === body.modelId);
    if (!entry) return { success: false, error: 'Модель не найдена' };
    if (this.serving && this.serving.file === entry.file) await this.stop();
    try { fs.unlinkSync(entry.path); } catch (e) { return { success: false, error: String(e.message || e) }; }
    return { success: true };
  }

  // ── serving ────────────────────────────────────────────────────────
  async start(body = {}) {
    const wanted = String(body.modelId || this.config.selectedModel || '').trim();
    const entry = this.listModels().find(m => m.id === wanted || m.file === wanted)
      || this.listModels()[0];
    if (!entry) {
      return { success: false, error: 'Нет скачанных моделей. Сначала /api/local-ai/downloads.' };
    }
    const bin = this._binary();
    if (!bin) {
      return { success: false, error: 'llama.cpp не установлен. Сначала /api/local-ai/runtimes/downloads.' };
    }
    if (this.serving && this.serving.file === entry.file && this.server && !this.server.killed) {
      return { success: true, alreadyRunning: true, ...this.serving };
    }
    await this.stop();

    const port = parseInt(process.env.ZEN_LOCAL_AI_PORT || '8791', 10) || 8791;
    // Threads: all cores. Context capped at 8192 - the runner has the RAM for
    // more, but the KV cache for a 128k window on CPU costs more than it buys.
    const args = [
      '-m', entry.path, '--port', String(port), '--host', '127.0.0.1',
      '-c', String(Math.min(entry.ctx || 8192, 8192)),
      '-t', String(os.cpus().length || 2),
      '--no-webui'
    ];
    this.server = spawn(bin, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let log = '';
    this.server.stdout.on('data', d => { log += d.toString().slice(-2000); });
    this.server.stderr.on('data', d => { log += d.toString().slice(-2000); });
    this.server.on('exit', () => { this.serving = null; });

    // llama-server answers /health only once the weights are loaded, which
    // for a 4 GB model on CPU is tens of seconds. Poll rather than sleep.
    const deadline = Date.now() + 180000;
    while (Date.now() < deadline) {
      if (this.server.killed || this.server.exitCode !== null) {
        return { success: false, error: 'llama-server завершился: ' + log.slice(-300) };
      }
      const ok = await this._probe(port);
      if (ok) {
        this.serving = { modelId: entry.id, file: entry.file, port, startedAt: Date.now() };
        this.config.selectedModel = entry.id;
        this.config.engines.llamacpp.model = entry.file;
        this._saveConfig();
        return { success: true, ...this.serving };
      }
      await new Promise(r => setTimeout(r, 1500));
    }
    await this.stop();
    return { success: false, error: 'llama-server не поднялся за 3 минуты: ' + log.slice(-300) };
  }

  _probe(port) {
    return new Promise(resolve => {
      const req = http.get({ host: '127.0.0.1', port, path: '/health', timeout: 2000 }, res => {
        res.resume();
        resolve(res.statusCode === 200);
      });
      req.on('error', () => resolve(false));
      req.on('timeout', () => { req.destroy(); resolve(false); });
    });
  }

  async stop() {
    if (!this.server) { this.serving = null; return { success: true }; }
    const proc = this.server;
    this.server = null;
    this.serving = null;
    try { proc.kill('SIGTERM'); } catch {}
    await new Promise(r => setTimeout(r, 400));
    try { if (proc.exitCode === null) proc.kill('SIGKILL'); } catch {}
    return { success: true };
  }

  async status() {
    const models = this.listModels();
    const bin = this._binary();
    return {
      success: true,
      available: !!bin && models.length > 0,
      runtimeInstalled: !!bin,
      runtimePath: bin || null,
      modelsDownloaded: models.length,
      serving: this.serving ? { ...this.serving } : null,
      storagePath: this.root(),
      hfTokenConfigured: !!hfToken()
    };
  }

  publicConfig() {
    return {
      available: true,
      storagePath: this.root(),
      runtimeRoot: this.runtimeRoot(),
      activeEngine: this.config.activeEngine,
      selectedModel: this.config.selectedModel,
      engines: this.config.engines,
      serving: this.serving ? { ...this.serving } : null,
      hfTokenConfigured: !!hfToken()
    };
  }

  configure(body = {}) {
    if (body.selectedModel) {
      this.config.selectedModel = String(body.selectedModel);
      this.config.engines.llamacpp.model = String(body.selectedModel);
    }
    if (body.activeEngine) this.config.activeEngine = String(body.activeEngine);
    this._saveConfig();
    return this.publicConfig();
  }

  /**
   * One call that does whatever is still missing: fetch the runtime, fetch the
   * model, start the server. The hub needs a single button, not a checklist.
   */
  async prepare(body = {}) {
    const modelId = String(body.modelId || '').trim();
    const entry = CATALOG.find(m => m.id === modelId);
    if (!entry) return { success: false, error: `Неизвестная модель '${modelId}'` };

    if (!this._binary()) {
      const t = await this.startRuntimeDownload();
      const deadline = Date.now() + 300000;
      while (Date.now() < deadline && t.status === 'running') await new Promise(r => setTimeout(r, 1000));
      if (t.status === 'error') return { success: false, error: 'llama.cpp: ' + t.message };
    }
    if (!fs.existsSync(path.join(this.root(), entry.file))) {
      const t = this.startDownload({ modelId });
      if (t.status === 'error') return { success: false, error: t.message };
      const deadline = Date.now() + 3600000;
      while (Date.now() < deadline && t.status === 'running') await new Promise(r => setTimeout(r, 2000));
      if (t.status === 'error') return { success: false, error: t.message };
    }
    return await this.start({ modelId });
  }

  // ── inference ──────────────────────────────────────────────────────
  async chat(body = {}) {
    if (!this.serving) {
      const started = await this.start({ modelId: body.model || this.config.selectedModel });
      if (!started.success) return { success: false, error: started.error, text: '' };
    }
    const payload = JSON.stringify({
      model: this.serving.modelId,
      messages: body.messages || [],
      temperature: body.temperature ?? 0.5,
      max_tokens: body.max_tokens ?? 2048,
      stream: false
    });
    return await new Promise(resolve => {
      const req = http.request({
        host: '127.0.0.1', port: this.serving.port, path: '/v1/chat/completions',
        method: 'POST', timeout: 300000,
        headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }
      }, res => {
        let raw = '';
        res.setEncoding('utf8');
        res.on('data', c => raw += c);
        res.on('end', () => {
          try {
            const json = JSON.parse(raw);
            const msg = json.choices?.[0]?.message || {};
            resolve({
              success: true,
              text: msg.content || '',
              model: this.serving ? this.serving.modelId : 'local',
              usage: json.usage || {}
            });
          } catch {
            resolve({ success: false, error: 'Локальный сервер вернул не-JSON: ' + raw.slice(0, 200), text: '' });
          }
        });
      });
      req.on('error', e => resolve({ success: false, error: String(e.message || e), text: '' }));
      req.on('timeout', () => { req.destroy(); resolve({ success: false, error: 'Таймаут локальной модели', text: '' }); });
      req.write(payload);
      req.end();
    });
  }
}

module.exports = { LocalAiManager, CATALOG };
