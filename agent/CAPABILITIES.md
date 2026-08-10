# Capabilities — инструменты, которые агент делает себе сам

## Зачем это отдельно от `custom_tool_*` и `plugin_*`

В агенте уже были два способа саморасширения, и оба исполняют код в песочнице `vm`.
Их валидатор отклоняет исходник, в котором встречается `require`, `process`,
`child_process`, `import` или `eval` — это не настройка, а явная проверка в
`zen-agent.js`:

```js
if (/\brequire\s*\(|\bprocess\b|child_process|\bimport\s*\(|\beval\s*\(/.test(code)) {
  return { error: 'Custom tool не может использовать require/process/import/eval …' };
}
```

Поэтому подключиться к ADB, поднять RDP, запустить программу и снять её
скриншот через `custom_tool_create` **невозможно в принципе** — такой код не
дадут даже сохранить. Capability — это второй слой, у которого другой контракт:
он запускается настоящим интерпретатором в отдельном процессе.

| | `custom_tool_*` / `plugin_*` | `capability_*` |
|---|---|---|
| Исполнение | `vm` внутри процесса агента | отдельный процесс |
| `require`, `process`, сеть | запрещены | доступны |
| Языки | только JS | Python, Node, Bash |
| Зависимости | нет | `pip`, `npm`, `apt` |
| Долгие задачи | нет, лимит 15 с | да, фон + логи |
| Файлы наружу | нет | папка `artifacts/` |
| Подтверждение | как у обычного инструмента | как у `execute_command` |

Старый слой не удалён: для чистой функции над текстом песочница по-прежнему
правильный и более безопасный выбор.

## Устройство

```
<workspace>/.zen-agent/capabilities/<имя>/
  manifest.json     имя, описание, runtime, зависимости, параметры
  main.py|main.js|main.sh
  artifacts/        то, что capability произвела (скриншоты, отчёты, логи)
  logs/             stdout/stderr фоновых запусков
```

Аргументы приходят JSON-ом двумя путями сразу — в переменной окружения
`CAPABILITY_ARGS` и на `stdin`, чтобы их мог прочитать любой язык. Ещё доступны
`CAPABILITY_ARTIFACTS` (куда писать результат), `CAPABILITY_DIR` и
`CAPABILITY_CWD`.

Если последняя строка `stdout` — JSON-объект, он возвращается уже разобранным в
поле `result`, а не текстом. Всё новое в `artifacts/` возвращается списком
путей; PNG оттуда можно сразу отдать в `vision_analyze` или `ocr_image` — так
замыкается цикл «сделал программу → запустил → посмотрел, что получилось».

## Инструменты

| Инструмент | Что делает |
|---|---|
| `capability_templates()` | показать готовые шаблоны |
| `capability_list()` | что уже создано |
| `capability_create({name, template \| code, runtime, description, pip, npm, system})` | создать |
| `capability_install({name})` | поставить зависимости (pip / npm / apt) |
| `capability_run({name, args, cwd, background, timeout_ms})` | запустить |
| `capability_logs({name, limit})` | логи фонового запуска |
| `capability_stop({name})` | остановить фоновый запуск |
| `capability_inspect({name})` | код, параметры, артефакты |
| `capability_delete({name})` | удалить |

## Windows

Windows-раннер — это не Linux с другими именами пакетов, поэтому у него свои
шаблоны и свой runtime.

| | Linux | Windows |
|---|---|---|
| Runtime по умолчанию | `python` / `bash` | `powershell` (pwsh есть всегда) |
| Пакеты | `apt-get` | `choco`, иначе `winget` |
| RDP | ставить xrdp + XFCE | встроенная служба, ставить нечего |
| Скриншот | Xvfb + ImageMagick | `System.Drawing` с реального экрана |

`capability_templates()` показывает только то, что работает на текущей машине;
`{all:true}` покажет остальные. Если всё же создать чужой шаблон, ответ придёт
с полем `platformWarning` — молча нерабочего инструмента не будет.

Системные пакеты можно объявить сразу для обеих ОС, и `capability_install`
выберет нужное:

```
capability_create({ name: "adb", description: "…", runtime: "powershell",
                    code: "…", system: { linux: ["android-tools-adb"], windows: ["adb"] } })
```

Сессия Windows поднимается через `Remote session` → `os: windows`. Это всегда
RDP: браузерного noVNC там нет, а `mode` к Windows не применяется. Машина
мощнее linux-раннера (4 ядра, 16 ГБ), но собрать Android-APK на ней нельзя.

## Готовые шаблоны

* **`adb_bridge`** — `adb connect` по Wi-Fi или уже подключённое USB-устройство,
  выполнение shell-команд, выгрузка `logcat`, скриншот экрана телефона.
  Для диагностики Eden на устройстве: `dumpsys gfxinfo`, вылеты, `logcat`.
* **`rdp_session`** — `xrdp` + XFCE на машине агента, возвращает логин, пароль и
  порт.
* **`gui_screenshot`** — поднимает `Xvfb`, запускает GUI-программу, делает серию
  PNG. Это и есть «создать программу для ПК и снять скрины её работы».
* **`pytest_runner`** — прогон `pytest`/`unittest` с машинно-читаемым отчётом.
* **`http_probe`** — код ответа, задержка по нескольким замерам, заголовки.

Только для Windows:

* **`windows_rdp`** — включает встроенный RDP, заводит пользователя, открывает
  правило брандмауэра, возвращает логин/пароль/порт.
* **`windows_screenshot`** — запускает программу и снимает реальный экран через
  `System.Drawing`; виртуальный дисплей не нужен, он уже есть.
* **`windows_adb`** — ADB через platform-tools, включая поиск `adb.exe` там,
  куда его кладут choco и Android SDK.
* **`windows_system`** — инвентарь: ОС, CPU, память, диски, GPU, службы, порты.

Шаблон — стартовая точка: после `capability_create` код лежит на диске, его
можно читать и править обычными `read_file` / `edit_file`.

## Пример

```
capability_create({ name: "shots", template: "gui_screenshot" })
capability_install({ name: "shots" })          # xvfb, imagemagick
capability_run({ name: "shots", args: { command: "python3 app.py", seconds: 5, shots: 2 } })
vision_analyze({ path: "…/artifacts/shot-01.png", prompt: "Что на экране?" })
```

## Границы, о которых стоит знать заранее

* **ADB по USB** требует физически подключённого к машине агента телефона. На
  раннере GitHub Actions его нет. Реально работает `adb connect` по Wi-Fi, и
  только если устройство доступно по сети с раннера, — для телефона за NAT
  нужен туннель.
* **RDP наружу** упирается в ngrok: на бесплатном плане статический домен
  работает только по HTTP, а `ngrok tcp` выдаёт случайный адрес.
* **`apt-get`** нужен `sudo` без пароля. На раннере он есть, в Termux — нет.
* Таймаут по умолчанию 120 с, максимум час; фоновые запуски не ограничены и
  живут до `capability_stop`.

## Тесты

`tests/t_capabilities.js`, 25 проверок, запускаются вместе с остальной сюитой
через `tools/run_tests.sh`. Они не мокают процессы: capability реально
создаётся, реально запускается, у неё проверяются код возврата, таймаут,
артефакты и то, что PID отличается от PID агента. Отдельный шаг в `tests.yml`
поднимает настоящий `Xvfb` и убеждается, что снятый PNG не пустой.
