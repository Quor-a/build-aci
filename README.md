# BuildAci — 基于 ACI 框架的端侧 APK 构建受控端

BuildAci 是一个 Android 应用，同时作为 **Zorv AI** 的 ACI 受控端（Agent-Controlled Interface）。它能在**设备本地**把一个最小 Android 工程真正编译、链接、转 dex、签名成可安装的 APK——即「被 Zorv AI 受控的、能在端侧开发/构建安卓 APK 的 App」。

> 设计原则：**绝不假装能编**。工具链缺失时，检测函数如实报告「缺哪个、放哪」，构建步骤直接报错退出，不会伪造成功。把工具补齐后，同一套代码即可在设备上真出包。

## 功能（v1.0.0）

- **端侧 APK 构建器**（构建 Tab）：
  - 一键生成「最小可编译 Android 工程」模板（单 Java `Activity` + 资源 + `AndroidManifest.xml`）。
  - 一键构建：真实跑通 `aapt2 compile → aapt2 link → ecj 编译 → d8 转 dex → 组装未签名 APK → apksigner 签名`（v2/v3 签名，免 zipalign）。
  - 实时进度条 + 分步结果 + 完整构建日志。
  - 产物 APK 一键「安装」（通过 `FileProvider` 调起系统安装器）。
- **工具链自检**（工具链 Tab）：逐项显示 aapt2 / ecj / d8 / apksigner / android.jar / framework-res / 签名密钥 / dalvikvm 运行时是否齐备，并给出补齐路径。
- **ACI 受控端（8 项能力）**：Zorv AI 可在后台静默初始化工程、写入源码、触发构建、轮询进度与日志。
- **调试操控台**（操控台 Tab）：自绑定 ACI Service，可视化双通道状态与能力列表，手动填参调 `call()`。

## ACI 能力清单

| 能力 | 说明 |
| --- | --- |
| `build_tools` | 工具链状态（缺哪个放哪） → tools / allReady |
| `build_init` | 生成默认工程（path/force）→ root / ok |
| `build_set_source` | 写入源/资源文件（path/relPath/content）→ ok |
| `build_list` | 列出工程文件（path）→ files / count |
| `build_assemble` | 启动一次完整构建（path）→ jobId（异步，轮询） |
| `build_status` | 查询构建状态（jobId）→ state（running/step/progress/apkPath/finished/success/steps） |
| `build_logs` | 获取构建日志（jobId/tail）→ logs |
| `build_stop` | 终止构建任务（jobId）→ stopped |

构建是长任务，`build_assemble` 立即返回 `jobId`，由 `build_status` / `build_logs` 轮询——契合控制端 15s 调用预算（单步 ACI 调用必须快速返回）。

## 真实构建流水线（工具链齐备时）

```
工程根/
  AndroidManifest.xml
  src/com/example/buildapp/MainActivity.java
  res/values/strings.xml
  res/drawable/ic_launcher.xml
        │
        ├─ aapt2 compile --dir res -o build/res.flata
        ├─ aapt2 link -I framework-res.apk --manifest ... -R res.flata → build/linked.apk
        ├─ ecj -d build/classes -cp android.jar src  → *.class
        ├─ d8 --output build/dex --lib android.jar --min-api 21 build/classes → classes.dex
        ├─ 组装：linked.apk + classes.dex（STORED）+ 工程 assets/lib → build/unsigned.apk
        └─ apksigner sign --ks debug.keystore（v1 关闭，仅 v2/v3）→ build/app-debug.apk
```

- **纯 Java 工具**（ecj / d8 / apksigner）经设备自带 `/system/bin/dalvikvm` 在子进程运行，避免这些工具内部 `System.exit` 杀掉宿主 App。
- **aapt2** 为原生二进制，直接 `exec`。
- **debug.keystore** 已内置（alias `androiddebugkey` / password `android`），随 APK 打包，开箱可签名。

## 工具链补齐（详见 [TOOLCHAIN.md](TOOLCHAIN.md)）

把下列文件放到对应位置即可「真出包」：

| 文件 | 类型 | 放置位置 |
| --- | --- | --- |
| `aapt2` | 原生（须匹配设备 ABI） | `assets/libs/<abi>/` 或 `filesDir/toolchain/<abi>/` |
| `ecj.jar` | 纯 Java | `assets/libs/common/` 或 `filesDir/toolchain/common/` |
| `d8.jar` | 纯 Java | 同上 common |
| `apksigner.jar` | 纯 Java | 同上 common |
| `android.jar` | 平台框架桩 | 同上 common |
| `framework-res.apk` | 平台资源（可选） | toolchain 目录，缺则依赖 `/system/framework/framework-res.apk` |

默认设备 ABI 由 `Build.SUPPORTED_ABIS[0]` 决定（如 `arm64-v8a`）。纯 Java 工具可取自 Android SDK `build-tools/` 与 `ecj-*.jar`；aapt2 需 Android 版移植二进制（如 Termux / AIDE 提供的对应 ABI 版本）。

## 已知限制

- **需补齐工具链**：本仓库不打包 aapt2/ecj/d8/apksigner/android.jar（体积与许可原因）。补齐前，UI 与 ACI 会如实报告缺失，不会伪造成功。
- **Java 优先**：默认模板为 Java 工程。Kotlin 工程需另行提供 `kotlinc`，本版未集成。
- **签名仅 v2/v3**：关闭 v1 签名以规避 zipalign 对齐要求，现代 Android（7.0+）安装无碍；如需 v1 兼容旧设备，请先 zipalign 再 v1 签名。
- **沙箱**：构建在工程目录（`filesDir/buildproject/`）内进行，产物经 `FileProvider` 暴露给系统安装器安装（需「未知来源」授权）。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- ACI 框架：`aidl-aci-core`（AIDL + LocalSocket 抽象命名空间双通道）
- 构建：纯 `ProcessBuilder` 编排 aapt2 / dalvikvm，无 GMS 依赖

## 构建（开发机编译本 App）

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 接入 Zorv AI

作为第三方受控端，遵循《ACI 开发者手册》§16：剥离 AAR 内 `ai.aci.permission.*` 定义节点（`tools:node="remove"`），仅引用 Zorv AI 主程序已定义的权限，避免异签名安装冲突。

## 许可

开源许可（见仓库 LICENSE）。
