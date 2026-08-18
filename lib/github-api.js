// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Working on GitHub directly, without cloning anything.
//
// WHY
//   Every git tool here needed a checkout first: git_clone into work/, then
//   set_workspace, then edit, then commit, then push. On a phone-driven runner
//   that is minutes of waiting and gigabytes of disk before a one-line change,
//   and the clone is thrown away when the session ends. The REST API edits a
//   file in place: one request to read, one to write, and the commit exists.
//
//   Cloning still wins for anything that needs the whole tree - a build, a
//   test run, a refactor across files. This is for the common case of reading
//   and changing a handful of files in a repository you are not sitting in.
//
// AUTHENTICATION
//   The same token the rest of the agent uses. Whatever the token can see,
//   these tools can see; whatever it can write, these can write.

'use strict';
const https = require('https');

const API = 'api.github.com';

function request(token, method, urlPath, body) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
    const req = https.request({
      hostname: API, port: 443, path: urlPath, method, timeout: 45000,
      headers: {
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        'User-Agent': 'symbiosis-agent',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(payload ? { 'Content-Type': 'application/json', 'Content-Length': payload.length } : {})
      }
    }, res => {
      let raw = '';
      res.setEncoding('utf8');
      res.on('data', c => raw += c);
      res.on('end', () => {
        let parsed = null;
        try { parsed = raw ? JSON.parse(raw) : {}; } catch { parsed = { raw: raw.slice(0, 400) }; }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          // The API's own message is far more useful than a status code:
          // "Resource not accessible by integration" says the token lacks a
          // scope, "Not Found" on a write usually means the same thing.
          const msg = (parsed && parsed.message) || `HTTP ${res.statusCode}`;
          reject(new Error(`GitHub ${res.statusCode}: ${msg}`));
          return;
        }
        resolve(parsed);
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('GitHub API timeout')));
    if (payload) req.write(payload);
    req.end();
  });
}

/** owner/name, or a full URL, or the repo the session is bound to. */
function normaliseRepo(repo, fallback) {
  const value = String(repo || fallback || '').trim()
    .replace(/^https?:\/\/github\.com\//i, '')
    .replace(/\.git$/i, '')
    .replace(/\/+$/, '');
  if (!/^[\w.-]+\/[\w.-]+$/.test(value)) return null;
  return value;
}

const enc = p => String(p || '').split('/').filter(Boolean).map(encodeURIComponent).join('/');

class GitHubApi {
  constructor(getToken, getDefaultRepo) {
    this.getToken = getToken;
    this.getDefaultRepo = getDefaultRepo || (() => '');
  }

  _repo(args) {
    const repo = normaliseRepo(args.repo, this.getDefaultRepo());
    if (!repo) {
      throw new Error('Нужен repo в виде owner/name (или задай его пресетом по умолчанию).');
    }
    return repo;
  }

  _token() {
    const t = this.getToken();
    if (!t) throw new Error('Нет GitHub-токена. Задай SYMBIOSIS_KEY или GITHUB_TOKEN.');
    return t;
  }

  /** Read a file straight from the repository. */
  async readFile(args = {}) {
    const repo = this._repo(args);
    const p = String(args.path || '').trim();
    if (!p) return { error: 'Нужен path.' };
    const ref = args.ref ? `?ref=${encodeURIComponent(args.ref)}` : '';
    const data = await request(this._token(), 'GET', `/repos/${repo}/contents/${enc(p)}${ref}`);
    if (Array.isArray(data)) {
      return { error: `${p} — это папка. Используй github_list.` };
    }
    if (data.encoding !== 'base64' || typeof data.content !== 'string') {
      return { error: 'Файл не текстовый или слишком большой для этого API (>1 МБ).' };
    }
    const content = Buffer.from(data.content, 'base64').toString('utf8');
    return { repo, path: p, sha: data.sha, size: data.size, content };
  }

  /**
   * Create or replace a file. One request, one commit, no working copy.
   *
   * The sha of the existing file is required by the API for an update and is
   * fetched automatically - it is also what makes this safe against a lost
   * update: if someone else changed the file meanwhile, the sha no longer
   * matches and GitHub rejects the write instead of silently overwriting.
   */
  async writeFile(args = {}) {
    const repo = this._repo(args);
    const p = String(args.path || '').trim();
    if (!p) return { error: 'Нужен path.' };
    if (typeof args.content !== 'string') return { error: 'Нужен content (строка).' };
    const message = String(args.message || `Update ${p}`).trim();

    let sha = args.sha;
    if (!sha) {
      try {
        const existing = await request(this._token(), 'GET',
          `/repos/${repo}/contents/${enc(p)}${args.branch ? `?ref=${encodeURIComponent(args.branch)}` : ''}`);
        if (!Array.isArray(existing)) sha = existing.sha;
      } catch (e) {
        if (!/404/.test(e.message)) throw e;   // 404 = new file, which is fine
      }
    }
    const body = {
      message,
      content: Buffer.from(args.content, 'utf8').toString('base64'),
      ...(sha ? { sha } : {}),
      ...(args.branch ? { branch: args.branch } : {})
    };
    const out = await request(this._token(), 'PUT', `/repos/${repo}/contents/${enc(p)}`, body);
    return {
      repo, path: p,
      commit: out.commit && out.commit.sha ? out.commit.sha.slice(0, 7) : null,
      url: out.content && out.content.html_url,
      created: !sha
    };
  }

  async deleteFile(args = {}) {
    const repo = this._repo(args);
    const p = String(args.path || '').trim();
    if (!p) return { error: 'Нужен path.' };
    const current = await request(this._token(), 'GET', `/repos/${repo}/contents/${enc(p)}`);
    if (Array.isArray(current)) return { error: 'Это папка, а не файл.' };
    const out = await request(this._token(), 'DELETE', `/repos/${repo}/contents/${enc(p)}`, {
      message: String(args.message || `Delete ${p}`),
      sha: current.sha,
      ...(args.branch ? { branch: args.branch } : {})
    });
    return { repo, path: p, deleted: true, commit: out.commit && out.commit.sha.slice(0, 7) };
  }

  async list(args = {}) {
    const repo = this._repo(args);
    const p = String(args.path || '').replace(/^\/+/, '');
    const ref = args.ref ? `?ref=${encodeURIComponent(args.ref)}` : '';
    const data = await request(this._token(), 'GET', `/repos/${repo}/contents/${enc(p)}${ref}`);
    const rows = Array.isArray(data) ? data : [data];
    return {
      repo, path: p || '/',
      entries: rows.map(e => ({ name: e.name, type: e.type, size: e.size, path: e.path }))
    };
  }

  /**
   * Several files in one commit, through the git data API.
   *
   * The contents API writes one file per commit; a change spanning five files
   * would litter the history with five commits and leave the branch broken in
   * between. This builds a tree and commits it once.
   */
  async commitFiles(args = {}) {
    const repo = this._repo(args);
    const files = Array.isArray(args.files) ? args.files : [];
    if (!files.length) return { error: 'Нужен files: [{path, content}].' };
    const message = String(args.message || 'Update files').trim();
    const token = this._token();

    const branch = args.branch || (await request(token, 'GET', `/repos/${repo}`)).default_branch;
    const ref = await request(token, 'GET', `/repos/${repo}/git/ref/heads/${enc(branch)}`);
    const baseSha = ref.object.sha;
    const baseCommit = await request(token, 'GET', `/repos/${repo}/git/commits/${baseSha}`);

    const tree = [];
    for (const f of files) {
      if (!f || !f.path) return { error: 'У каждого файла нужен path.' };
      if (f.delete) {
        tree.push({ path: f.path, mode: '100644', type: 'blob', sha: null });
        continue;
      }
      const blob = await request(token, 'POST', `/repos/${repo}/git/blobs`, {
        content: Buffer.from(String(f.content ?? ''), 'utf8').toString('base64'),
        encoding: 'base64'
      });
      tree.push({ path: f.path, mode: '100644', type: 'blob', sha: blob.sha });
    }

    const newTree = await request(token, 'POST', `/repos/${repo}/git/trees`, {
      base_tree: baseCommit.tree.sha, tree
    });
    const commit = await request(token, 'POST', `/repos/${repo}/git/commits`, {
      message, tree: newTree.sha, parents: [baseSha]
    });
    await request(token, 'PATCH', `/repos/${repo}/git/refs/heads/${enc(branch)}`, {
      sha: commit.sha, force: false
    });
    return {
      repo, branch, commit: commit.sha.slice(0, 7), files: files.length,
      url: `https://github.com/${repo}/commit/${commit.sha}`
    };
  }

  async search(args = {}) {
    const q = String(args.query || '').trim();
    if (!q) return { error: 'Нужен query.' };
    const repo = args.repo === null ? null : normaliseRepo(args.repo, this.getDefaultRepo());
    const scoped = repo ? `${q} repo:${repo}` : q;
    const data = await request(this._token(), 'GET',
      `/search/code?q=${encodeURIComponent(scoped)}&per_page=${Math.min(30, Number(args.limit) || 15)}`);
    return {
      total: data.total_count,
      matches: (data.items || []).map(i => ({ path: i.path, repo: i.repository.full_name, url: i.html_url }))
    };
  }

  async commits(args = {}) {
    const repo = this._repo(args);
    const qs = new URLSearchParams({ per_page: String(Math.min(50, Number(args.limit) || 10)) });
    if (args.path) qs.set('path', args.path);
    if (args.branch) qs.set('sha', args.branch);
    const data = await request(this._token(), 'GET', `/repos/${repo}/commits?${qs}`);
    return {
      repo,
      commits: (data || []).map(c => ({
        sha: c.sha.slice(0, 7),
        message: (c.commit.message || '').split('\n')[0],
        author: c.commit.author && c.commit.author.name,
        date: c.commit.author && c.commit.author.date
      }))
    };
  }

  async branches(args = {}) {
    const repo = this._repo(args);
    const data = await request(this._token(), 'GET', `/repos/${repo}/branches?per_page=50`);
    const info = await request(this._token(), 'GET', `/repos/${repo}`);
    return {
      repo, default: info.default_branch,
      branches: (data || []).map(b => ({ name: b.name, sha: b.commit.sha.slice(0, 7), protected: !!b.protected }))
    };
  }

  async createBranch(args = {}) {
    const repo = this._repo(args);
    const name = String(args.name || '').trim();
    if (!name) return { error: 'Нужен name новой ветки.' };
    const from = args.from || (await request(this._token(), 'GET', `/repos/${repo}`)).default_branch;
    const ref = await request(this._token(), 'GET', `/repos/${repo}/git/ref/heads/${enc(from)}`);
    await request(this._token(), 'POST', `/repos/${repo}/git/refs`, {
      ref: `refs/heads/${name}`, sha: ref.object.sha
    });
    return { repo, branch: name, from, created: true };
  }

  async pullRequest(args = {}) {
    const repo = this._repo(args);
    const head = String(args.head || '').trim();
    if (!head) return { error: 'Нужна head-ветка.' };
    const base = args.base || (await request(this._token(), 'GET', `/repos/${repo}`)).default_branch;
    const pr = await request(this._token(), 'POST', `/repos/${repo}/pulls`, {
      title: String(args.title || head), head, base, body: String(args.body || '')
    });
    return { repo, number: pr.number, url: pr.html_url, state: pr.state };
  }

  async repoInfo(args = {}) {
    const repo = this._repo(args);
    const d = await request(this._token(), 'GET', `/repos/${repo}`);
    return {
      repo: d.full_name, description: d.description, private: d.private,
      defaultBranch: d.default_branch, language: d.language,
      pushedAt: d.pushed_at, openIssues: d.open_issues_count,
      size: d.size, url: d.html_url
    };
  }

  async myRepos(args = {}) {
    const data = await request(this._token(), 'GET',
      `/user/repos?per_page=${Math.min(100, Number(args.limit) || 30)}&sort=pushed`);
    return {
      repos: (data || []).map(r => ({
        name: r.full_name, private: r.private, pushedAt: r.pushed_at,
        language: r.language, defaultBranch: r.default_branch
      }))
    };
  }

  async runs(args = {}) {
    const repo = this._repo(args);
    const data = await request(this._token(), 'GET',
      `/repos/${repo}/actions/runs?per_page=${Math.min(30, Number(args.limit) || 10)}`);
    return {
      repo,
      runs: (data.workflow_runs || []).map(r => ({
        name: r.name, status: r.status, conclusion: r.conclusion,
        branch: r.head_branch, commit: r.head_sha.slice(0, 7),
        created: r.created_at, url: r.html_url
      }))
    };
  }

  async dispatch(args = {}) {
    const repo = this._repo(args);
    const wf = String(args.workflow || '').trim();
    if (!wf) return { error: 'Нужен workflow, например panel-apk.yml' };
    await request(this._token(), 'POST',
      `/repos/${repo}/actions/workflows/${encodeURIComponent(wf)}/dispatches`,
      { ref: args.ref || 'main', ...(args.inputs ? { inputs: args.inputs } : {}) });
    return { repo, workflow: wf, dispatched: true };
  }
}

module.exports = { GitHubApi, normaliseRepo };
