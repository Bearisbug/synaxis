"""RUN-077 group R: the English fallbacks of RushHud against the language files of the client mod.

Usage: <py> docs/test-runs/RUN-077/R/fallbacks.py [<lang directory>]
Standard library only, paths relative to the repository root this file sits in. Exit code 0 when every
rush.* key of RushHud has a fallback equal to its en_us.json text, both language files carry exactly
those rush.* keys, and RushPlugin#translationKeys() is RushHud.keys(); 1 otherwise.
"""
import json
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[4]
lang = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else root / "client-fabric/src/main/resources/assets/synaxis/lang"
source = root / "game-rush/src/main/java/io/github/bearisbug/synaxis/game/rush"
hud = (source / "RushHud.java").read_text(encoding="utf-8")
plugin = (source / "RushPlugin.java").read_text(encoding="utf-8")

fallbacks = dict(re.findall(r'Map\.entry\("(rush\.[^"]+)", "((?:[^"\\]|\\.)*)"\)', hud))
problems = []
files = {name: json.loads((lang / name).read_text(encoding="utf-8")) for name in ("en_us.json", "zh_cn.json")}
for name, texts in files.items():
    keys = {key for key in texts if key.startswith("rush.")}
    if keys != set(fallbacks):
        problems.append(f"{name}: rush keys differ: only in the file {sorted(keys - set(fallbacks))}, "
                        f"only in RushHud {sorted(set(fallbacks) - keys)}")
for key, text in sorted(fallbacks.items()):
    if files["en_us.json"].get(key) != text:
        problems.append(f"{key}: fallback {text!r} is not en_us.json {files['en_us.json'].get(key)!r}")
if "return RushHud.keys();" not in plugin:
    problems.append("RushPlugin#translationKeys() does not return RushHud.keys()")

print(f"{len(fallbacks)} rush keys with a fallback; problems: {problems}")
sys.exit(1 if problems else 0)
