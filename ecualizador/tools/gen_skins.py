import json, sys, re
D, KT = sys.argv[1], sys.argv[2]  # KT: WinampClassicSkins.kt
vis = json.load(open(D + '/viscolors.json')); vis.update(json.load(open(D + '/viscolors2.json')))
chrome = json.load(open(D + '/chrome.json'))
names = dict((i, n) for n, i in re.findall(r'WinampSkin\(\s*"([^"]+)", "([^"]+)",\s*rgb', open(KT).read()) if i != 'base-2.91')
assert set(names) == set(vis), set(vis) ^ set(names)
def dist(a, b): return sum(abs(x - y) for x, y in zip(a, b))
def hexs(cs): return ', '.join('0x%02X%02X%02X' % tuple(c) for c in cs)
lines = []
for sid in sorted(vis, key=lambda k: names[k].lower()):
    v = vis[sid]; c = chrome[sid]
    face = c['face']; frame = c['frame']; accent = c['accent']
    text = c.get('text', v[12]); textbg = c.get('textbg', v[0])
    pn = c.get('pl_normal', v[12]); pc = c.get('pl_current', v[23]); pb = c.get('pl_normalbg', v[0])
    if dist(pn, pb) < 90: pn, pb = v[12], v[0]
    if dist(pc, pb) < 90: pc = v[23]
    # La letra debe leerse sobre su fondo: si no, el primer color propio del skin que contraste.
    for cand in (text, pn, pc, accent, v[23], v[2], v[12]):
        if dist(cand, textbg) >= 220: text = cand; break
    else:
        text = (255, 255, 255) if sum(textbg) < 384 else (0, 0, 0)
    lines.append(f'        WinampSkin(\n            "{names[sid]}", "{sid}",\n            rgb({hexs(v)}),\n            rgb({hexs([face, frame, accent, text, textbg, pn, pc, pb])}),\n        ),')
src = open(KT).read()
head = src[:src.index('    val ALL')]
src = head + '    val ALL: List<WinampSkin> = listOf(\n' + '\n'.join(lines) + '\n    )\n}\n'
open(KT, 'w').write(src)
print(len(lines))
