# MaiHoku

MaiHoku 是一个基于 LibXposed API 101 的 Android Xposed 模块，将两个 Hook 合并到同一个 APK：

- `com.miui.screenrecorder`：在支持 120 Hz 的设备上启用屏幕录制 120 FPS 选项。
- `com.termux`：双击终端左侧边缘时打开侧边栏。

模块使用静态作用域，只会加载到上述两个目标包。统一入口会根据当前包名初始化对应 Hook，两个功能互不影响。

## 从旧模块迁移

MaiHoku 使用新的应用 ID `io.github.xiaotong6666.maihoku`。安装并启用 MaiHoku 后，请在 Xposed 管理器中禁用或卸载原来的 `sr120hook` 和 `TermuxHook`，避免同一个功能被重复 Hook。

## 构建

```bash
./gradlew assembleRelease
```

生成的 APK 位于 `app/build/outputs/apk/release/`。

构建环境：Compile SDK 36、Min SDK 26、Java 11、LibXposed API 101。

默认从 Maven Central 获取 EzXHelper。如需调试 EzXHelper 本地源码，可将 `EZXHELPER_PATH` 指向其项目目录后再构建。
