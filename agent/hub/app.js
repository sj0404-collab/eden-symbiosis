// Intentionally minimal.
//
// The upstream agent serves /hub/app.js and the xterm vendor bundle for a
// terminal widget that is not part of this distribution - requesting them
// produced 500s in the browser console. index.html here is self-contained, so
// this file exists only to keep those requests quiet and to say why.
//
// The in-browser terminal needs `ws` and `node-pty`, which the agent reports
// as disabled when they are absent. Use the MCP tool `execute_command`
// instead; it runs on the same machine and needs no native modules.
console.info('[zen-hub] index.html is self-contained; app.js is a placeholder.');
