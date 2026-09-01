"""Generate a GitHub-flavoured table of contents for README.md and insert it.

Slugs follow GitHub's rules: lowercase, drop anything that is not alphanumeric /
space / hyphen / underscore (so backticks, dots and colons vanish), spaces to
hyphens, then -1/-2 suffixes for duplicates.
"""
import io
import re

PATH = 'README.md'
MARK_START = '<!-- toc -->'
MARK_END = '<!-- /toc -->'


def slug(text, seen):
    s = text.strip().lower()
    s = re.sub(r'[^a-z0-9 _-]', '', s)
    s = s.replace(' ', '-')
    n = seen.get(s, 0)
    seen[s] = n + 1
    return s if n == 0 else '%s-%d' % (s, n)


def strip_markup(text):
    # Link text only, and drop emphasis/code markers from the label.
    text = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', text)
    return text.replace('`', '').replace('**', '').strip()


src = io.open(PATH, encoding='utf-8').read()
lines = src.split('\n')

# Headings, skipping anything inside a fenced code block.
in_fence = False
in_toc = False
headings = []
for raw in lines:
    # Skip the generated block itself, or regeneration indexes its own heading.
    if raw.strip() == MARK_START:
        in_toc = True
        continue
    if raw.strip() == MARK_END:
        in_toc = False
        continue
    if in_toc:
        continue
    if raw.strip().startswith('```'):
        in_fence = not in_fence
        continue
    if in_fence:
        continue
    m = re.match(r'^(#{2,3})\s+(.*)$', raw)
    if m:
        headings.append((len(m.group(1)), m.group(2).rstrip()))

seen = {}
entries = []
for level, text in headings:
    entries.append((level, strip_markup(text), slug(text, seen)))

toc = [MARK_START, '## Contents', '']
for level, label, anchor in entries:
    indent = '' if level == 2 else '  '
    toc.append('%s- [%s](#%s)' % (indent, label, anchor))
toc.append('')
toc.append(MARK_END)
toc_block = '\n'.join(toc)

if MARK_START in src:
    src = re.sub(re.escape(MARK_START) + r'.*?' + re.escape(MARK_END), toc_block, src, flags=re.S)
else:
    first = next(i for i, l in enumerate(lines) if re.match(r'^##\s+', l))
    lines = lines[:first] + toc_block.split('\n') + [''] + lines[first:]
    src = '\n'.join(lines)

io.open(PATH, 'w', encoding='utf-8', newline='\n').write(src)

# Self-check: every anchor emitted must correspond to a heading we generated.
valid = {a for _, _, a in entries}
bad = [a for a in re.findall(r'\]\(#([^)]+)\)', toc_block) if a not in valid]
print('headings indexed:', len(entries))
print('duplicate slugs disambiguated:', sum(1 for k, v in seen.items() if v > 1))
print('broken TOC anchors:', bad or 'none')
