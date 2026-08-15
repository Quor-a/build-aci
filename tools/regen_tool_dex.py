#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重建 BuildAci 的三个端侧 dexed 工具 jar：ecj_dex.jar / d8_dex.jar / apksigner_dex.jar。

关键修复（对应真机崩溃）：
  * d8 只把 .class 编成 classes.dex，**会丢弃 jar 内的资源文件**（.properties / .rsc / kotlin_builtins / help.txt / META-INF/services 等）。
  * 但 ecj 的 Main 构造器调用 relocalize() 要加载 ResourceBundle `messages.properties`，
    缺失即 MissingResourceException；parser 还要 .rsc；d8 可能要 kotlin_builtins。
  * 这些资源必须由「同一个被 DexClassLoader 加载的 jar」提供，否则运行时找不到。

本脚本：
  1) 对 ecj：合并 ecj.jar 类 + JDK java.compiler 模块(javax.lang.model/tools/processing，修复 NoClassDefFound)，再 d8。
     对 d8/apksigner：直接用各自源 jar 的 .class 即可。
  2) d8 输出 classes.dex。
  3) 重新打包 jar = classes.dex(STORED 不压缩) + 源 jar 的全部非 class 资源(DEFLATED)。
  4) zipalign -p 4 保证 classes.dex 4 字节对齐（Android 16 / ColorOS 上 ART 直接 mmap，无需设备端抽取）。

用法: python3 tools/regen_tool_dex.py
依赖: JDK17 (java.exe), Android build-tools zipalign, 以及 assets/libs/common 下的源 jar/d8.jar/android.jar。
"""
import io
import os
import shutil
import subprocess
import zipfile

ROOT = r"D:/Calw OS-project/BuildAci"
COMMON = os.path.join(ROOT, "app", "src", "main", "assets", "libs", "common")
JAVA = r"C:/Program Files/Java/jdk-17.0.11+9/bin/java.exe"
JMOD = r"C:/Program Files/Java/jdk-17.0.11+9/jmods/java.compiler.jmod"
ZIPALIGN = r"D:/Android/Sdk/build-tools/34.0.0/zipalign.exe"
WORK = r"D:/tmp/regen_tool_dex"
D8_JAR = os.path.join(COMMON, "d8.jar")
ANDROID_JAR = os.path.join(COMMON, "android.jar")

shutil.rmtree(WORK, ignore_errors=True)
os.makedirs(WORK, exist_ok=True)


def extract_jar_classes(src_jar, dest_dir, prefix=""):
    """把 src_jar 里所有 .class 解到 dest_dir（保留包路径，可加 prefix 如 classes/）。"""
    with zipfile.ZipFile(src_jar) as z:
        for n in z.namelist():
            if n.endswith(".class"):
                target = os.path.join(dest_dir, prefix + n)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(n) as r, open(target, "wb") as w:
                    w.write(r.read())


def extract_jmod_classes(jmod_path, dest_dir, subpkg):
    """从 jmod 里抽指定子包(如 classes/javax/)的 .class 到 dest_dir。"""
    data = open(jmod_path, "rb").read()
    idx = data.find(b"PK\x03\x04")
    with zipfile.ZipFile(io.BytesIO(data[idx:])) as z:
        for n in z.namelist():
            if n.startswith(subpkg) and n.endswith(".class"):
                arc = n[len("classes/"):]
                target = os.path.join(dest_dir, arc)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with z.open(n) as r, open(target, "wb") as w:
                    w.write(r.read())


def build_combined(classes_dir, combined_path):
    with zipfile.ZipFile(combined_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(classes_dir):
            for f in files:
                p = os.path.join(root, f)
                arc = os.path.relpath(p, classes_dir).replace(os.sep, "/")
                z.write(p, arc)


def d8_dex(combined_path, dex_out_dir):
    os.makedirs(dex_out_dir, exist_ok=True)
    subprocess.run(
        [JAVA, "-Xmx1g", "-cp", D8_JAR, "com.android.tools.r8.D8",
         "--min-api", "26", "--lib", ANDROID_JAR, "--output", dex_out_dir, combined_path],
        check=True,
    )
    dex = os.path.join(dex_out_dir, "classes.dex")
    if not os.path.exists(dex):
        raise RuntimeError("d8 未产出 classes.dex: " + dex_out_dir)
    return dex


def repackage(src_resource_jar, classes_dex, out_jar):
    """classes.dex STORED + 源 jar 全部非 class 资源 DEFLATED -> out_jar。"""
    tmp = out_jar + ".tmp"
    with zipfile.ZipFile(tmp, "w") as z:
        # 资源
        with zipfile.ZipFile(src_resource_jar) as sz:
            for n in sz.namelist():
                if n.endswith(".class") or n.endswith("/"):
                    continue
                zi = zipfile.ZipInfo(n)
                zi.compress_type = zipfile.ZIP_DEFLATED
                z.writestr(zi, sz.read(n))
        # classes.dex
        with open(classes_dex, "rb") as f:
            data = f.read()
        zi = zipfile.ZipInfo("classes.dex")
        zi.compress_type = zipfile.ZIP_STORED
        z.writestr(zi, data)
    # zipalign -f 4（4 字节对齐 STORED 条目；-f 强制覆盖已有输出）
    subprocess.run([ZIPALIGN, "-f", "4", tmp, out_jar], check=True)
    os.remove(tmp)


def verify(out_jar, must_contain):
    with zipfile.ZipFile(out_jar) as z:
        names = z.namelist()
        dex = z.getinfo("classes.dex")
        dex_stored = dex.compress_type == zipfile.ZIP_STORED
    print(f"  [{os.path.basename(out_jar)}] 条目={len(names)} classes.dex STORED={dex_stored}")
    for mc in must_contain:
        ok = any(mc in n for n in names)
        print(f"    含 {mc!r}: {ok}")
        if not ok:
            raise RuntimeError(f"{out_jar} 缺少资源 {mc}")


def process_ecj():
    print("=== ecj_dex.jar ===")
    indir = os.path.join(WORK, "ecj_in")
    os.makedirs(indir, exist_ok=True)
    extract_jar_classes(os.path.join(COMMON, "ecj.jar"), indir)
    # ecj.jar 把三组 javax 类型以「去前缀的错误路径」存进来了（.class 内部仍声明真实
    # javax.* 包名，但目录被砍掉 javax/ 前缀），导致运行时按正确路径 javax/... 查找
    # 时 NoClassDefFound：
    #   lang/model/**       -> javax/lang/model/**
    #   annotation/processing/** -> javax/annotation/processing/**
    #   tools/**            -> javax/tools/**
    # 处理：把这三棵重定位到正确 javax/ 路径（版本与 ecj 自身精确一致，不补 jmod，
    # 避免 JDK 版本错配 / 重复定义）。
    reloc = {
        os.path.join(indir, "lang", "model"): os.path.join(indir, "javax", "lang", "model"),
        os.path.join(indir, "annotation", "processing"): os.path.join(indir, "javax", "annotation", "processing"),
        os.path.join(indir, "tools"): os.path.join(indir, "javax", "tools"),
    }
    for src, dst in reloc.items():
        if os.path.isdir(src):
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            # 若目标已存在则先合并
            for root, _, files in os.walk(src):
                for f in files:
                    sp = os.path.join(root, f)
                    rp = os.path.relpath(sp, src).replace(os.sep, "/")
                    dp = os.path.join(dst, rp)
                    os.makedirs(os.path.dirname(dp), exist_ok=True)
                    if not os.path.exists(dp):
                        os.rename(sp, dp)
            shutil.rmtree(src, ignore_errors=True)
    combined = os.path.join(WORK, "ecj_combined.jar")
    build_combined(indir, combined)
    dex = d8_dex(combined, os.path.join(WORK, "ecj_dex"))
    repackage(os.path.join(COMMON, "ecj.jar"), dex, os.path.join(COMMON, "ecj_dex.jar"))
    # 资源(jar 条目)校验
    verify(os.path.join(COMMON, "ecj_dex.jar"),
           ["org/eclipse/jdt/internal/compiler/batch/messages.properties",
            "org/eclipse/jdt/internal/compiler/parser/parser1.rsc",
            "org/eclipse/jdt/internal/compiler/problem/messages.properties"])
    # 类(dex 内)校验：javax.* 三族必须进 dex（重定位后的正确路径版本）
    dexpath = os.path.join(COMMON, "ecj_dex.jar")
    tmpdex = os.path.join(WORK, "classes.dex")
    with zipfile.ZipFile(dexpath) as z:
        z.extract("classes.dex", WORK)
    dd = r"D:/Android/Sdk/build-tools/34.0.0/dexdump.exe"
    out = subprocess.run([dd, "-l", "plain", tmpdex], capture_output=True, text=True).stdout
    for cls in ("javax/lang/model/SourceVersion", "javax/tools/DiagnosticCollector",
                "javax/annotation/processing/Processor"):
        ok = cls in out
        print(f"    dex 含 {cls}: {ok}")
        if not ok:
            raise RuntimeError(f"{dexpath} dex 缺少类 {cls}")


def process_simple(name):
    print(f"=== {name}_dex.jar ===")
    indir = os.path.join(WORK, f"{name}_in")
    os.makedirs(indir, exist_ok=True)
    src = os.path.join(COMMON, f"{name}.jar")
    extract_jar_classes(src, indir)
    combined = os.path.join(WORK, f"{name}_combined.jar")
    build_combined(indir, combined)
    dex = d8_dex(combined, os.path.join(WORK, f"{name}_dex"))
    repackage(src, dex, os.path.join(COMMON, f"{name}_dex.jar"))
    # 资源样例校验
    with zipfile.ZipFile(src) as z:
        res = [n for n in z.namelist() if not n.endswith(".class") and not n.endswith("/")]
    verify(os.path.join(COMMON, f"{name}_dex.jar"), res[:1] if res else ["META-INF/MANIFEST.MF"])


if __name__ == "__main__":
    import sys
    # 默认只重建 ecj（修 NoClassDefFound + 缺资源）。d8/apksigner 是 R8 预编译产物，
    # 含 Java nest mates，自行重 dex 会因缺 nest 同伴类失败，且当前流程不依赖它们
    # 缺失的资源（Java 编译用不到 kotlin_builtins，apksigner 走 API 非 CLI），保持原状。
    # 如需强制重建 d8/apksigner，可 `python regen_tool_dex.py --all`。
    if "--all" in sys.argv:
        process_ecj()
        process_simple("d8")
        process_simple("apksigner")
    else:
        process_ecj()
    print("ALL DONE")
