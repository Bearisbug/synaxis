import json, pathlib, sys
d = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'client-fabric/src/main/resources/assets/synaxis/lang')
need = (['synaxis:action-cooldown']
        + ['synaxis.action.' + c for c in ('ok', 'rejected', 'cooldown', 'rate-limited', 'stale-scene', 'unknown-action')]
        + ['key.synaxis.action_1', 'key.category.synaxis.play']
        + ['rush.' + k for k in ('scene.lobby', 'scene.round', 'action.queue', 'action.back', 'hint', 'hint.failed',
                                 'timer.countdown', 'timer.round', 'objective', 'score', 'capture', 'result', 'result.row')])
files = {f: json.loads((d / f).read_text(encoding='utf-8')) for f in ('en_us.json', 'zh_cn.json')}
missing = [f + ' ' + k for f, t in files.items() for k in need if k not in t]
one_sided = sorted(set(files['en_us.json']) ^ set(files['zh_cn.json']))
print(len(need), 'keys checked; missing:', missing, '; in one file only:', one_sided)
for f, t in files.items():
    print(f, [t.get(k) for k in ('rush.hint', 'rush.hint.failed')])
sys.exit(1 if missing or one_sided else 0)
