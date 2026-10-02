"""Compile the reviewed catalog into Android resources and stable text keys.

English is the default pack. New packs add a translations[tag] field to every
catalog message and a language in localization/languages.json. No UI edit is needed.
"""
from pathlib import Path
import argparse
import json
import re
from collections import Counter
from xml.sax.saxutils import escape

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--check', action='store_true', help='Validate generated files without writing')
args = parser.parse_args()
entries = json.loads((root/'localization/catalog.json').read_text(encoding='utf-8'))
languages = json.loads((root/'localization/languages.json').read_text(encoding='utf-8'))
assert len({e['key'] for e in entries}) == len(entries), 'Duplicate text key'
assert len({e['source'] for e in entries}) == len(entries), 'Duplicate source template'
assert {l['tag'] for l in languages} >= {'en', 'ru'}
assert len({l['tag'] for l in languages}) == len(languages)
placeholder = re.compile(r'@(\d+)(?:\|(track))?@')
output = {}

def resource_text(value):
    return escape(value.replace('\\n', '\n')).replace("'", "\\'").replace('"', '\\"')

def runtime_source(value):
    return value.replace('\\n', '\n').replace('\\"', '"')

def translated(entry, tag):
    return entry.get('translations', {}).get(tag, entry['english'] if tag == 'en' else entry['source'] if tag == 'ru' else None)

def kotlin(value):
    return json.dumps(runtime_source(value), ensure_ascii=False).replace('$', '\\$')

for language in languages:
    tag = language['tag']
    assert re.fullmatch('[a-z]{2,3}', tag), 'Language tag must match an Android values qualifier'
    lines = ['<?xml version="1.0" encoding="utf-8"?>', '<resources>']
    for entry in entries:
        value = translated(entry, tag)
        assert value, (tag, entry['key'], 'Missing translation')
        assert Counter(m[0] for m in placeholder.findall(value)) == Counter(m[0] for m in placeholder.findall(entry['source'])), (tag, entry['key'], 'Changed placeholder')
        assert not re.findall(r'@\d[^@]*@', placeholder.sub('', value)), (tag, entry['key'], 'Malformed placeholder')
        if tag != 'ru' or tag in entry.get('translations', {}):
            assert Counter(placeholder.findall(value)) == Counter(placeholder.findall(entry['english'])), (tag, entry['key'], 'Changed count formatting')
        assert value.startswith(' ') == entry['source'].startswith(' ') and value.endswith(' ') == entry['source'].endswith(' '), (tag, entry['key'], 'Changed envelope spacing')
        lines.append(f'    <string name="{entry["key"]}" formatted="false">"{resource_text(value)}"</string>')
    quantities = language['trackCount']
    assert 'other' in quantities
    assert set(quantities) <= {'zero', 'one', 'two', 'few', 'many', 'other'}
    assert all(text.startswith('%d ') and text.count('%d') == 1 for text in quantities.values()), (tag, 'Track count requires one leading number')
    lines.append('    <plurals name="track_count">' + ''.join(f'<item quantity="{quantity}">{escape(text)}</item>' for quantity, text in quantities.items()) + '</plurals>')
    lines.append('</resources>')
    folder = 'values' if tag == 'en' else f'values-{tag}'
    output[f'localization/src/main/res/{folder}/strings.xml'] = '\n'.join(lines)+'\n'

lines = ['package dev.petrov.ymplayer2.localization', '', '/** Generated from catalog.json; IDs remain stable when adding languages. */', 'enum class Msg(val source: String, val english: String, val resource: Int) {']
for entry in entries:
    source = json.dumps(runtime_source(entry['source']), ensure_ascii=False).replace('$','\\$')
    english = json.dumps(runtime_source(entry['english']), ensure_ascii=False).replace('$','\\$')
    lines.append(f'    {entry["key"]}({source}, {english}, R.string.{entry["key"]}),')
lines[-1] = lines[-1][:-1]+';'
lines.append('}')
output['localization/src/main/kotlin/dev/petrov/ymplayer2/localization/Msg.kt'] = '\n'.join(lines)+'\n'
lines = ['package dev.petrov.ymplayer2.localization', '', '/** Generated from complete language packs only. */', 'internal val supportedLanguages = listOf(']
for language in sorted(languages,key=lambda l:l['englishName']):
    fields = ', '.join(json.dumps(language[k],ensure_ascii=False) for k in ('tag','englishName','nativeName'))
    lines.append(f'    AppLanguage({fields}),')
lines.append(')')
output['localization/src/main/kotlin/dev/petrov/ymplayer2/localization/SupportedLanguages.kt'] = '\n'.join(lines)+'\n'

# Explicit app-owned variants let a cached status follow the next language choice.
# Split initializers to stay below the JVM method-size limit as packs grow.
patterns = []
for entry in entries:
    variants = [(language['tag'], translated(entry, language['tag'])) for language in languages]
    if ('ru', entry['source']) not in variants:
        variants.append(('ru', entry['source']))
    patterns.extend((entry['key'], tag, value) for tag, value in variants)
chunks = [patterns[offset:offset+80] for offset in range(0, len(patterns), 80)]
lines = ['package dev.petrov.ymplayer2.localization', '',
         '/** Generated, reviewed app text only; not a translation dictionary for user data. */',
         'internal data class MessagePattern(val key: Msg, val language: String, val source: String)',
         'internal val trackCountForms = mapOf(']
for language in languages:
    forms = ', '.join(kotlin(form) for form in dict.fromkeys(language['trackCount'].values()))
    lines.append(f'    {kotlin(language["tag"])} to listOf({forms}),')
lines.extend([')', '', 'internal val messagePatterns = buildList {'])
for index in range(len(chunks)):
    lines.append(f'    addAll(patterns{index}())')
lines.append('}')
for index, chunk in enumerate(chunks):
    lines.extend(['', f'private fun patterns{index}() = listOf('])
    lines.extend(f'    MessagePattern(Msg.{key}, {kotlin(tag)}, {kotlin(value)}),' for key, tag, value in chunk)
    lines.append(')')
output['localization/src/main/kotlin/dev/petrov/ymplayer2/localization/MessagePatterns.kt'] = '\n'.join(lines)+'\n'

for name, contents in output.items():
    p = root/name
    if args.check:
        assert p.exists() and p.read_text(encoding='utf-8') == contents, ('Stale generated file', name)
    else:
        p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text(contents,encoding='utf-8')
print(f'Validated {len(entries)} messages, {len(languages)} complete language packs; ' + ('generated files current.' if args.check else 'generated files written.'))
