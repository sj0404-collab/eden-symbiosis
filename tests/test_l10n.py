# -*- coding: utf-8 -*-
"""Проверка русской локализации по правилам Android string resources."""
import re, sys, json
import xml.etree.ElementTree as ET

# Термины, которые НАМЕРЕННО не переводятся: имена собственные и устоявшиеся
# в русскоязычном сообществе заимствования.
INTENTIONAL_SAME = {'tab_homebrew'}

def load(p):
    return {e.get('name'): ''.join(e.itertext())
            for e in ET.parse(p).getroot().iter('string')}

def run(en_path, ru_path, mine_path):
    en, ru = load(en_path), load(ru_path)
    mine = set(json.load(open(mine_path)).keys())
    fails, warns = [], []

    raw = open(ru_path, encoding='utf-8').read()
    entries = re.findall(r'<string name="([^"]+)">(.*?)</string>', raw, re.S)

    # Дубликаты ключей ломают сборку ресурсов.
    keys = [k for k, _ in entries]
    dup = {k for k in keys if keys.count(k) > 1}
    if dup: fails.append(f"дубли ключей: {sorted(dup)[:5]}")

    # Аргументы формата обязаны совпадать, иначе IllegalFormatException.
    fm = lambda s: sorted(re.findall(r'%(\d+\$[sd]|[sd%])', s))
    bad = [k for k in ru if k in en and fm(en[k]) != fm(ru[k])]
    if bad: fails.append(f"формат не совпадает: {bad[:5]}")

    # Неэкранированный апостроф — ошибка компиляции ресурсов.
    unesc = [k for k, v in entries
             if any(c == "'" and (i == 0 or v[i-1] != '\\') for i, c in enumerate(v))]
    if unesc: fails.append(f"апострофы без экранирования: {unesc[:5]}")

    # Сырой & недопустим в XML.
    amp = [k for k, v in entries if re.search(r'&(?!amp;|lt;|gt;|quot;|apos;|#)', v)]
    if amp: fails.append(f"неэкранированный &: {amp[:5]}")

    empty = [k for k, v in ru.items() if not v.strip()]
    if empty: fails.append(f"пустые строки: {empty[:5]}")

    same = [k for k in mine
            if k in ru and k in en and ru[k] == en[k]
            and len(en[k]) > 3 and k not in INTENTIONAL_SAME]
    if same: fails.append(f"не переведено: {same[:5]}")

    missing = [k for k in mine if k not in ru]
    if missing: fails.append(f"отсутствует в RU: {missing[:5]}")

    # Кириллица должна реально присутствовать в переводах-предложениях.
    no_cyr = [k for k in mine
              if k in ru and len(ru[k]) > 25 and not re.search(r'[А-Яа-я]', ru[k])]
    if no_cyr: warns.append(f"нет кириллицы: {no_cyr[:5]}")

    return fails, warns, len(en), len(ru), len(mine)

if __name__ == '__main__':
    f, w, ne, nr, nm = run('values/strings.xml', 'values-ru/strings.xml', '/tmp/mine.json')
    print(f"EN: {ne} строк | RU: {nr} строк | проверяется моих: {nm}\n")
    for x in f: print("  ПРОВАЛ:", x)
    for x in w: print("  ВНИМАНИЕ:", x)
    print("\nРЕЗУЛЬТАТ:", "ВСЕ ТЕСТЫ ПРОЙДЕНЫ" if not f else "ЕСТЬ ОШИБКИ")
    sys.exit(1 if f else 0)
