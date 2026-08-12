// Exercise parseJsonToolCall on the real file, including the exact reply that
// broke the hub.
const fs = require('fs');
const src = fs.readFileSync('agent/zen-agent.js', 'utf8');
const start = src.indexOf('function extractBalancedJsonObject');
const end = src.indexOf('function validateToolArguments');
eval(src.slice(start, end));

let bad = 0;
const check = (name, got, want) => {
  const ok = JSON.stringify(got) === JSON.stringify(want);
  console.log((ok ? 'ok   ' : 'FAIL ') + name + '  ' + JSON.stringify(got));
  if (!ok) bad++;
};

// The exact string that caused "Задача завершена" for every message.
check('<tool_call> с голым именем',
  parseJsonToolCall('<tool_call>workspace_info</tool_call>'),
  { tool: 'workspace_info', args: {} });

check('<tool_call> с JSON',
  parseJsonToolCall('<tool_call>{"tool":"read_file","args":{"path":"/tmp/a"}}</tool_call>'),
  { tool: 'read_file', args: { path: '/tmp/a' } });

check('<tool_call> среди текста',
  parseJsonToolCall('Сейчас посмотрю.\n<tool_call>workspace_info</tool_call>'),
  { tool: 'workspace_info', args: {} });

// The old formats must keep working.
check('TOOL_JSON по-прежнему работает',
  parseJsonToolCall('TOOL_JSON:{"tool":"workspace_info","args":{}}'),
  { tool: 'workspace_info', args: {} });

check('json в ```-блоке',
  parseJsonToolCall('```json\n{"tool":"health_check","args":{"url":"http://x"}}\n```'),
  { tool: 'health_check', args: { url: 'http://x' } });

// Prose that merely mentions a tool must NOT fire one.
check('обычный текст не вызывает инструмент',
  parseJsonToolCall('Могу выполнить workspace_info, если нужно.'), null);
check('пустой tool_call игнорируется',
  parseJsonToolCall('<tool_call></tool_call>'), null);
check('мусор внутри тега игнорируется',
  parseJsonToolCall('<tool_call>сделай что-нибудь хорошее</tool_call>'), null);
check('ответ "Ну" без тегов', parseJsonToolCall('Ну'), null);

console.log(bad ? `ПРОВАЛОВ: ${bad}` : 'ВСЁ ПРОШЛО');
process.exit(bad ? 1 : 0);
