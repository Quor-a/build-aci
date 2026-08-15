#!/usr/bin/env bash
# regen_ecj_dex.sh —— 重生成 BuildAci 的进程内 ecj dex 工具 jar
#
# 根因（v1.5.14 修复）：ecj 的 batch 编译器 FileSystem.<clinit> 在初始化时引用
#   javax.lang.model.SourceVersion（以及 javax.tools.* / javax.annotation.processing.*）。
# 这些类属于 JDK 的 java.compiler 模块，Android 运行时完全没有，而 assets 里的
# ecj.jar 也不打包它们 → 真机上 NoClassDefFoundError → InvocationTargetException: null。
#
# 修复：把整个 java.compiler 模块的类合并进 ecj，再 d8 成 dex。
# 本脚本即为该流程的可复现实现。改了 ecj 版本或 JDK 后重跑即可。
#
# 用法（在 Git Bash / WSL 下，需 Windows 风格路径给 java/d8）：
#   bash tools/regen_ecj_dex.sh
#
set -euo pipefail

# ---- 路径配置（按需修改）----
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMON="$PROJECT_ROOT/app/src/main/assets/libs/common"
ECJ="$COMMON/ecj.jar"            # 源 ecj.jar（会被注入 javax.* 后原地更新）
ECJ_DEX="$COMMON/ecj_dex.jar"    # 产物：进程内加载的 dex jar
D8="$COMMON/d8.jar"              # 用于 dex 的 d8.jar
ANDROID_JAR="$COMMON/android.jar"
# JDK 17 的 java.compiler 模块（提供 javax.lang.model / javax.tools / javax.annotation.processing）
JMOD="${JDK17:-C:/Program Files/Java/jdk-17.0.11+9/jmods/java.compiler.jmod}"
JMOD_TOOL="${JDK17_BIN:-C:/Program Files/Java/jdk-17.0.11+9/bin/jmod}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[1/5] 解 java.compiler.jmod（jmod 头部有 4 字节前缀，unzip 解析失败，用 python 定位 PK 签名）"
python3 - "$JMOD" "$WORK/jc.zip" <<'PY'
import zipfile, sys
jp, out = sys.argv[1], sys.argv[2]
data = open(jp,'rb').read()
idx = data.find(b'PK\x03\x04')
open(out,'wb').write(data[idx:])
z = zipfile.ZipFile(out)
os.makedirs(r""+os.path.join(os.path.dirname(out),'jc'), exist_ok=True)
z.extractall(os.path.join(os.path.dirname(out),'jc'))
PY
JAVAX_DIR="$WORK/jc/classes/javax"

echo "[2/5] 合并 ecj.jar + javax.* -> combined.jar"
python3 - "$ECJ" "$JAVAX_DIR" "$WORK/combined.jar" <<'PY'
import zipfile, os, sys
src, javax_dir, out = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    for root,_,files in os.walk(javax_dir):
        for f in files:
            p = os.path.join(root,f)
            arc = os.path.relpath(p, javax_dir).replace(os.sep,'/')
            if arc not in zout.namelist():
                zout.write(p, arc)
print("combined entries:", len(zipfile.ZipFile(out).namelist()))
PY

echo "[3/5] d8 生成 ecj_dex.jar（min-api 26；传给 java/d8 的路径必须是 Windows 风格）"
java -Xmx1g -cp "$D8" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output "$ECJ_DEX" "$WORK/combined.jar"

echo "[4/5] 把 javax.* 也写回源 ecj.jar（保持源码与 dex 一致，避免下次从源码重新 dex 又丢）"
python3 - "$ECJ" "$JAVAX_DIR" <<'PY'
import zipfile, os, sys, tempfile, shutil
src, javax_dir = sys.argv[1], sys.argv[2]
tmp = src + ".tmp"
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED) as zout:
    for it in zin.infolist():
        zout.writestr(it, zin.read(it.filename))
    for root,_,files in os.walk(javax_dir):
        for f in files:
            p = os.path.join(root,f)
            arc = os.path.relpath(p, javax_dir).replace(os.sep,'/')
            if arc not in zout.namelist():
                zout.write(p, arc)
shutil.move(tmp, src)
PY

echo "[5/5] 校验：dexdump 确认 javax/lang/model/SourceVersion 在 dex 中"
DEXDUMP="${DEXDUMP:-D:/Android/Sdk/build-tools/34.0.0/dexdump.exe}"
python3 - "$ECJ_DEX" "$WORK/classes.dex" <<'PY'
import zipfile, sys
zp, out = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(zp)
for n in z.namelist():
    if n.endswith('.dex'):
        open(out,'wb').write(z.read(n))
PY
if [ -f "$DEXDUMP" ]; then
  "$DEXDUMP" -l plain "$WORK/classes.dex" 2>/dev/null | grep -q "javax/lang/model/SourceVersion" \
    && echo "  ✔ SourceVersion 已包含在 dex" || echo "  �“ SourceVersion 缺失！"
else
  echo "  (dexdump 未找到，跳过校验；请手动确认)"
fi

echo "完成："
echo "  $ECJ_DEX"
echo "  $ECJ (已注入 javax.*)"
echo "重新 assembleDebug 即可把新工具链打进 APK。"
