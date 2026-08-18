// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Preload (`node --require`) that points the agent's OpenRouter client at a
// local stub instead of openrouter.ai.
//
// The agent reaches OpenRouter through https.request directly, so unlike the
// Zen path there is no curl to swap out. Patching the module boundary is the
// least invasive way to exercise the real request-building code - message
// window, tool definitions, response parsing - against a server that can
// answer the way OpenRouter does, including its 400 for a malformed window.
//
// ZEN_TEST_OR_PORT selects the local port. Every other host is left alone.

'use strict';
const https = require('https');
const http = require('http');

const PORT = parseInt(process.env.ZEN_TEST_OR_PORT || '0', 10);
if (PORT) {
  const realRequest = https.request.bind(https);
  https.request = function patched(options, callback) {
    const host = typeof options === 'string' ? options : (options && (options.hostname || options.host));
    if (host !== 'openrouter.ai') return realRequest(options, callback);
    const opts = { ...options };
    delete opts.hostname; delete opts.host;
    opts.host = '127.0.0.1';
    opts.port = PORT;
    opts.protocol = 'http:';
    // The stub speaks plain HTTP; anything TLS-specific would be rejected.
    delete opts.rejectUnauthorized; delete opts.ca; delete opts.servername;
    return http.request(opts, callback);
  };
}
