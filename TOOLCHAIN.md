# 工具链补齐指南（BuildAci 真出包必读）

BuildAci 本身不打包构建所需的重型工具（aapt2 / ecj / d8 / apksigner / android.jar），原因：

1. **体积**：这些文件动辄数 MB～数十 MB，不宜塞进受控端 APK。
2. **许可/平台**：aapt2 是原生二进制，Google 不发布 Android 版，需移植版（按 ABI 区分）；`android.jar` 来自对应 SDK platform。

补齐后，BuildAci 即可在设备本地**真实**把工程编成可安装 APK。

## 一、文件清单与放置位置

按优先级，引擎会依次查找：

1. `Android/data/com.ai.assistance.quro.build/files/toolchain/<abi>/<file>`
2. `Android/data/com.ai.assistance.quro.build/files/toolchain/common/<file>`
3. APK 内置 `assets/libs/<abi>/<file>`（首次使用自动解压到 1）
4. APK 内置 `assets/libs/common/<file>`（首次使用自动解压到 1）

其中 `<abi>` 取设备 `Build.SUPPORTED_ABIS[0]`，常见：`arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`。
原生 aapt2 必须匹配设备 ABI；纯 Java 工具（jar）放 `common` 即可。

| 文件 | 类型 | 说明 / 来源 |
| --- | --- | --- |
| `aapt2` | 原生二进制 | **必须**按 ABI 提供。来源：Termux 包的 `aapt2`、AIDE 自带、或自行交叉编译。需 `chmod +x`（引擎会自动置可执行）。 |
| `ecj.jar` | 纯 Java | Eclipse 编译器。`ecj-*.jar`（来自 Eclipse JDT / Maven Central `org.eclipse.jdt:ecj`）。 |
| `d8.jar` | 纯 Java | Android SDK `build-tools/<ver>/lib/d8.jar`（或 `com.android.tools:r8` 中的 d8 入口）。 |
| `apksigner.jar` | 纯 Java | Android SDK `build-tools/<ver>/lib/apksigner.jar`。主类 `com.android.apksigner.ApkSignerTool`。 |
| `android.jar` | 平台框架桩 | 对应 `android.jar`（如 `platforms/android-34/android.jar`），编译 Java 时解析 `android.*`。 |
| `framework-res.apk` | 平台资源（可选） | 链接阶段 `-I` 用的平台资源包。缺省回退到设备 `/system/framework/framework-res.apk`。 |

> 签名密钥 **无需补齐**：仓库已内置 `debug.keystore`（alias `androiddebugkey` / password `android`，PKCS12），随 APK 打包，开箱可签名。

## 二、最省事的补齐方式

在 PC 上把上述文件按结构放进一个目录，再用 `adb push` 到设备：

```bash
# PC 侧准备（以 arm64-v8a 为例）
mkdir -p toolchain/arm64-v8a toolchain/common
cp aapt2                toolchain/arm64-v8a/
cp ecj.jar d8.jar apksigner.jar android.jar  toolchain/common/

adb push toolchain /sdcard/   # 然后进入 BuildAci「工具链」Tab 暂无自动导入；
                               # 实际可用支持 SAF/文件访问的方式把目录搬到
                               # Android/data/com.ai.assistance.quro.build/files/toolchain/
```

或在 BuildAci 工程模板里由 Zorv AI 通过 `build_set_source` 之外的文件通道（如 FileAci 受控端）写入 `files/toolchain/`。

## 三、运行原理

- **纯 Java 工具**（ecj / d8 / apksigner）：经设备 `/system/bin/dalvikvm` 在**子进程**运行
  （`dalvikvm -Xmx512m -cp <jar> <mainClass> <args>`），并把 `ANDROID_DATA` 指向可写的 App 目录，
  避免这些工具内部 `System.exit` 杀掉宿主 App，同时让 dalvik-cache 可写。
- **aapt2**：原生二进制，直接 `exec`，输出合并捕获。
- 每步带超时（compile/link 120s，ecj/d8/apksigner 180s），失败即终止并如实记录。

## 四、验证

打开 BuildAci → 「工具链」Tab 应全部显示 ✓。然后「构建」Tab → 「生成默认工程」→「构建 APK」，
待进度到 100% 且显示产物路径后点「安装 APK」。

若仍报错，看「构建日志」：aapt2 类错误多为资源/Manifest 问题；ecj 报 `android.*` 找不到即 `android.jar` 未放对；
d8 报缺少 class 即编译步骤未产出。

## 五、FAQ

- **没有 `/system/bin/dalvikvm`？** 部分 ROM 阉割了它。可改用 `app_process`（引擎会自动回退探测），或装 Termux 提供的运行时。
- **只想 Java 工程？** 默认模板即为 Java。Kotlin 需另行提供 `kotlinc` 及 Kotlin 标准库 jar，本版未集成。
- **想支持 v1 签名（Android 6 及以下）？** 先对 unsigned.apk 做 zipalign 4，再 `apksigner sign --v1-signing-enabled true`。本版默认仅 v2/v3。
