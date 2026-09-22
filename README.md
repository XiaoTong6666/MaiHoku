# MaiHoku

MaiHoku 是一个基于 libxposed API 102 的 Android Xposed 模块，目前包含以下功能：

- **MIUI Screen Recorder**：根据设备支持的刷新率启用 120/144 FPS 录屏选项。
- **Termux**：双击终端左侧边缘打开侧边栏。
- **Telegram**：
  - 自定义 MTProto API identity。
  - 登录 / SMS 兼容处理。
  - 联系人列表显示双向联系人标记。
  - Profile 页面显示用户 ID 与 DC，支持长按复制。
  - 支持普通转发与无来源转发，并记住上次选择。

当前作用域：

```text
com.miui.screenrecorder
com.termux
org.telegram.messenger
org.telegram.messenger.web
```

## Telegram API identity

在项目根目录的 `local.properties` 中配置：

```properties
telegram.api_id=<api-id>
telegram.api_hash=<api-hash>
```

两项必须同时提供。未配置时模块仍可正常构建，但 API identity override 默认关闭。

这些值会被编译进 APK，请不要把它们当作秘密存储。

## Telegram 配置

Telegram 功能通过 libxposed remote preferences 读取配置：

| Key | 默认值 | 说明 |
| --- | --- | --- |
| `identity.enabled` | 自动 | 启用 API identity override |
| `identity.api_id` | 构建配置 | API ID |
| `identity.api_hash` | 构建配置 | API Hash |
| `sms.profile_enabled` | `true` | 登录 / SMS 兼容 |
| `ui.mutual_contact_enabled` | `true` | 双向联系人标记 |
| `ui.profile_identity_enabled` | `true` | Profile ID / DC |
| `forward.source_free_enabled` | `true` | 无来源转发 |

无来源转发复用 Telegram 原有转发流程，仅切换 `forwardFromMyName`，不会绕过禁止转发或内容保护限制。

## 构建

```bash
./gradlew assembleRelease
```

输出：

```text
app/build/outputs/apk/release/
```

构建环境：

- Compile SDK 36
- Min SDK 26
- Java 17
- libxposed API 102

## CI

- 推送到 `main`：自动构建并更新 `nightly` Release。
- 推送 tag：发布对应正式 Release。

# Credits

[EzXHelper](https://github.com/KyuubiRan/EzXHelper)  
[DexKit](https://github.com/LuckyPray/DexKit)  
[libxposed API](https://github.com/libxposed/api)  
