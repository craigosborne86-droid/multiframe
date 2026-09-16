#!/bin/zsh
# Checks that every native method R8 kept in the release DEX has its symbol in
# libmultiframe.so, and reports the symbols whose methods the shrinker removed
# (unreachable in the app: test and bench entry points). A method R8 *renamed*
# would show as a DEX method with no symbol, which is the failure this exists
# to catch -- see app/proguard-rules.pro.
#
# Usage: tools/verify-native-bridge.sh [path/to/app-release.apk]
set -euo pipefail
APK="${1:-$HOME/Library/Caches/MultiframeBuild/app/outputs/apk/release/app-release.apk}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
NM="$(ls -d "$SDK"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-nm | sort -V | tail -1)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -q -o "$APK" -d "$WORK/apk"
"$NM" -D --defined-only "$WORK/apk/lib/arm64-v8a/libmultiframe.so" | grep -o 'Java_dev_multiframe_[A-Za-z0-9_]*' | sort -u > "$WORK/so.txt"
"$BUILD_TOOLS/dexdump" "$WORK"/apk/classes*.dex > "$WORK/dex.txt" 2>/dev/null
python3 - "$WORK/dex.txt" "$WORK/so.txt" <<'PY'
import re, sys
lines = open(sys.argv[1]).read().splitlines()
so = set(open(sys.argv[2]).read().split())
cls = name = None
natives = []
for line in lines:
    m = re.search(r"Class descriptor\s*:\s*'L([^;]+);'", line)
    if m:
        cls = m.group(1); continue
    m = re.search(r"^\s+name\s*:\s*'([^']+)'", line)
    if m:
        name = m.group(1); continue
    m = re.search(r"^\s+access\s*:\s*0x[0-9a-f]+ \((.*)\)", line)
    if m and 'NATIVE' in m.group(1) and cls and cls.startswith('dev/multiframe'):
        natives.append((cls, name))
def esc(s): return s.replace('_', '_1').replace('/', '_').replace('$', '_00024')
dex = {f"Java_{esc(c)}_{esc(n)}" for c, n in natives}
missing = sorted(dex - so)
removed = sorted(so - dex)
print(f"native methods kept in the release DEX: {len(dex)} across {len({c for c, _ in natives})} classes")
print(f"JNI symbols exported by libmultiframe.so: {len(so)}")
print(f"symbols whose methods the shrinker removed as unreachable: {len(removed)}")
for s in removed: print(f"  - {s}")
if missing:
    print("FAIL: DEX native methods with no symbol (renamed or mis-declared):")
    for s in missing: print(f"  - {s}")
    sys.exit(1)
print("OK: every kept native method has its symbol; no class or method was renamed.")
PY
