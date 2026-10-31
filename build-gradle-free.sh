#!/usr/bin/env bash
# Gradle-free build for DynamicIsland (pure Java, one compileOnly jar).
# Replicates what app/build.gradle + gradle/localization.gradle generate, then
# aapt2 -> javac -> d8 -> package -> zipalign -> apksigner.
#
# Requires: JDK 17, Android build-tools (aapt2/zipalign/apksigner), platform android-36,
# and R8. Adjust SDK/JDK/BT/PLAT/R8 below. Must run with `ulimit -v unlimited` (see below).
set -e

PROJ=$(cd "$(dirname "$0")" && pwd)
OUT=/scratch/work/dibuild
SDK=/scratch/work/sdk
JDK=$SDK/jdk
BT=$SDK/bt/android-14
PLAT=$SDK/platform/android-36
R8=/scratch/work/gp/lib/r8-8.9.35.jar
VCODE=111
VNAME=0.1.14-dev24
PKG=dev.android.dynamicisland

# The container's soft address-space cap (1.5 GB) is smaller than what the JVM reserves; the hard cap is
# unlimited, so lift it or javac/R8 die with "malloc failed to allocate N bytes".
ulimit -v unlimited 2>/dev/null || true
export PATH="$JDK/bin:$PATH"
export _JAVA_OPTIONS="-XX:+UseSerialGC -Xmx1500m -Xss1m -XX:CompressedClassSpaceSize=64m -XX:MaxMetaspaceSize=320m -XX:-TieredCompilation -Xshare:off -Djava.io.tmpdir=/scratch/work/jtmp"
mkdir -p /scratch/work/jtmp

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/genres" "$OUT/stubclasses" "$OUT/classes" "$OUT/dex" "$OUT/jres"

echo "== 1. generated sources (what Gradle would have made) =="
python3 - "$PROJ" "$OUT" <<'PY'
import json, os, sys
import xml.etree.ElementTree as ET

proj, out = sys.argv[1], sys.argv[2]

# --- FrostedShaderSource.java from the AGSL asset (app/build.gradle generateMaterialShader)
agsl = os.path.join(proj, 'app/src/main/resources/dev/android/dynamicisland/material/frosted.agsl')
code = open(agsl, encoding='utf-8').read()
target = os.path.join(out, 'gen/dev/android/dynamicisland/island/material/FrostedShaderSource.java')
os.makedirs(os.path.dirname(target), exist_ok=True)
open(target, 'w', encoding='utf-8').write(
    'package dev.android.dynamicisland.island.material;\n'
    'final class FrostedShaderSource {\n'
    '    static final String CODE = ' + json.dumps(code) + ';\n'
    '    private FrostedShaderSource() {}\n}\n')
print("   FrostedShaderSource.java:", len(code), "chars of AGSL")

# --- localization catalog (gradle/localization.gradle generateLocalizationCatalog)
AND = '{http://schemas.android.com/apk/res/android}'
def android_text(s):
    if s is None: return ''
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"': s = s[1:-1]
    out_s, i = [], 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            i += 1; n = s[i]
            if n == 'n': out_s.append('\n')
            elif n == 't': out_s.append('\t')
            elif n == 'r': out_s.append('\r')
            elif n == 'u' and i + 4 < len(s):
                out_s.append(chr(int(s[i+1:i+5], 16))); i += 4
            else: out_s.append(n)
        else: out_s.append(c)
        i += 1
    return ''.join(out_s)

def prop_value(v):
    return v.replace('\\', '\\\\').replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t').replace(' ', '\\ ')

res = os.path.join(proj, 'app/src/main/res')
config = ET.parse(os.path.join(res, 'xml/locales_config.xml')).getroot()
languages = [el.get(AND + 'name') for el in config if el.tag.endswith('locale')]
print("   locales:", ','.join(languages))

labels = {}
for lang in languages:
    d = 'values' if lang == 'en' else 'values-' + lang
    f = os.path.join(res, d, 'strings.xml')
    strings = {}
    if os.path.exists(f):
        for node in ET.parse(f).getroot():
            if not node.tag.endswith('string'): continue
            k = node.get('name')
            if k and (k.startswith('island_') or k.startswith('stage_label_')):
                strings[k] = android_text(node.text)
    t = os.path.join(out, 'jres/dev/android/dynamicisland/i18n', lang + '.properties')
    os.makedirs(os.path.dirname(t), exist_ok=True)
    with open(t, 'w', encoding='utf-8') as fh:
        for k in sorted(strings):
            fh.write(k + '=' + prop_value(strings[k]) + '\n')
    for k, v in strings.items():
        if k.startswith('stage_label_'):
            ident = k[len('stage_label_'):]
            split = ident.rfind('_')
            labels.setdefault(ident[:split], {}).setdefault(lang, {})[ident[split+1:]] = v

open(os.path.join(out, 'jres/dev/android/dynamicisland/i18n/languages.properties'), 'w', encoding='utf-8') \
    .write('supported=' + ','.join(languages) + '\n')
t = os.path.join(out, 'jres/dev/android/dynamicisland/rules/builtin-translations.json')
os.makedirs(os.path.dirname(t), exist_ok=True)
open(t, 'w', encoding='utf-8').write(json.dumps({'status_labels': labels}, indent=4, ensure_ascii=False) + '\n')
print("   i18n catalog + builtin-translations.json written")
PY

echo "== 2. hidden-API stub (compileOnly, must NOT reach the dex) =="
"$JDK/bin/javac" -encoding UTF-8 -source 17 -target 17 -nowarn \
  -classpath "$PLAT/android.jar" -d "$OUT/stubclasses" \
  "$PROJ/tools/framework-stubs/android/view/CompositionSamplingListener.java" 2>&1 | grep -v 'bootstrap class path' || true

echo "== 3. aapt2: resources + R.java =="
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/res.zip"
python3 - "$PROJ" "$OUT" <<'PY'
import sys
proj, out = sys.argv[1], sys.argv[2]
s = open(proj + '/app/src/main/AndroidManifest.xml', encoding='utf-8').read()
if 'package=' not in s.split('>')[0]:
    s = s.replace('<manifest xmlns:android="http://schemas.android.com/apk/res/android"',
                  '<manifest xmlns:android="http://schemas.android.com/apk/res/android"\n    package="dev.android.dynamicisland"', 1)
open(out + '/AndroidManifest.xml', 'w', encoding='utf-8').write(s)
print("   manifest prepared (package added)")
PY
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLAT/android.jar" \
  --manifest "$OUT/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 35 --target-sdk-version 36 \
  --version-code $VCODE --version-name "$VNAME" \
  -A "$PROJ/app/src/main/assets" \
  --auto-add-overlay

echo "== 4. javac =="
find "$PROJ/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/srcs.txt"
wc -l < "$OUT/srcs.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 17 -target 17 -nowarn -Xmaxerrs 1000 \
  -classpath "$PLAT/android.jar:$PROJ/app/libs/xposed-api-82.jar:$OUT/stubclasses" \
  -d "$OUT/classes" @"$OUT/srcs.txt" 2>&1 | grep -v 'bootstrap class path' || true
test -f "$OUT/classes/dev/android/dynamicisland/xposed/Entry.class" || { echo "COMPILE FAILED"; exit 1; }
echo "   compiled: $(find "$OUT/classes" -name '*.class' | wc -l) classes"

echo "== 5. d8 (xposed-api is compileOnly, so not bundled) =="
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$JDK/bin/java" -cp "$R8" com.android.tools.r8.D8 \
  --release --min-api 35 --lib "$PLAT/android.jar" \
  --output "$OUT/dex" @"$OUT/classes.txt"
ls -la "$OUT/dex"

echo "== 6. package =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes.dex )
# JVM resources (i18n catalog, rules) + the project's own src/main/resources
( cd "$OUT/jres" && find . -type f | sed 's|^\./||' | zip -q -X "$OUT/unsigned.apk" -@ )
( cd "$PROJ/app/src/main/resources" && find . -type f | sed 's|^\./||' | zip -q -X "$OUT/unsigned.apk" -@ )

echo "== 7. align + sign =="
# -p 4, not -P 16: the build-tools release here has no -P, and this app ships no native libraries.
KS=/scratch/work/gp/di.keystore
[ -f "$KS" ] || "$JDK/bin/keytool" -genkeypair -v -keystore "$KS" -alias dynamicisland -keyalg RSA \
  -keysize 3072 -validity 36500 -storepass di123456 -keypass di123456 \
  -dname "CN=Dynamic Island, O=sampple-korea" >/dev/null 2>&1
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias dynamicisland --ks-pass pass:di123456 \
  --key-pass pass:di123456 --out "$OUT/DynamicIsland-$VNAME.apk" "$OUT/aligned.apk"

echo "== 8. verify =="
"$BT/aapt2" dump badging "$OUT/DynamicIsland-$VNAME.apk" 2>/dev/null | head -1
"$BT/apksigner" verify "$OUT/DynamicIsland-$VNAME.apk" && echo "SIGNATURE OK"
unzip -l "$OUT/DynamicIsland-$VNAME.apk" | grep -E 'classes.dex|assets/xposed_init|i18n/|rules/' | head -8
ls -la "$OUT/DynamicIsland-$VNAME.apk"
