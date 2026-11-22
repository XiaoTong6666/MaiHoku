# MaiHoku

MaiHoku 是一个基于 libxposed API 102 的 Android Xposed 模块，目前包含以下功能：

- **MIUI Screen Recorder**：根据设备支持的刷新率启用 120/144 FPS 录屏选项。
- **Termux**：双击终端左侧边缘打开侧边栏。
- **Google Messages / AOSP Messaging**：在原生设置页注入「小米网络短信」入口，支持 SIM 激活、账号验证、联系人在线状态和网络短信收发。Push 前台服务负责连接恢复、消息持久化和回执；宿主适配器复用短信 App 的原生收件、会话和发送状态流程。当前 Push 连接仅支持 SIM 1，不同宿主版本仍需分别验证兼容性。
- **Google One**：查询 Google AI Ultra、Pro 等订阅优惠，查看免费期和续费价格，支持打开 Google Play 官方结算页。
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
com.google.android.apps.messaging
com.android.messaging
com.android.mms
com.android.phone
org.telegram.messenger
org.telegram.messenger.web
com.google.android.apps.subscriptions.red
```

## 源码结构

`app/src/main/java/io/github/xiaotong6666/maihoku/` 按功能边界组织源码：

```text
MainHook.kt               # Xposed 入口和目标包路由
MaiHokuLog.kt             # 统一日志
hook/                     # BaseHook、HookRuntime、HookDsl、HookRegistry、dexkit 公共机制
features/
  telegram/               # Telegram Hook、功能逻辑和 translation/
  termux/                 # 双击打开侧边栏
  screenrecorder/         # MIUI 高帧率录屏
  googleone/              # Google One 优惠查询和结算入口
  mimessage/
    core/                 # 核心服务和运行状态
    account/              # SIM 身份、激活、Passport、Mixin
    transport/            # Xiaomi Push、Slim、XML、编解码
    presence/             # 联系人解析和在线状态
    message/              # 收发、持久化、回执
    ipc/                  # 跨 UID 通信和身份校验
    host/common/          # 短信宿主通用桥接和设置
    host/aosp/            # AOSP Messaging 适配
    host/google/          # Google Messages 适配
    telephony/            # 特权电话进程 Hook
    ui/                   # 激活、CAPTCHA 和资源帮助类
```

## Google One 优惠查询

此功能参考 google-ultra-main 的 Frida Billing 查询链，使用 MaiHoku 的 libxposed API 102 实现。

在 Xposed 管理器中为 MaiHoku 勾选 Google One（`com.google.android.apps.subscriptions.red`）作为作用域，重启 Google One 后，点击右下角「查询优惠」即可查看当前 Google Play 返回的订阅优惠。

- **查询范围**：Google AI Ultra、Google AI Pro 等方案，目前包含 `g1.play.20tb`、`g1.play.ai.pro`、`g1.30tb` 和 `g1.2tb.ai.subs_variant`。
- **优惠详情**：列出每个商品返回的 Offer，显示免费首期、计费周期及后续续费价格；优先展示带免费首期的优惠。
- **订阅方式**：选择优惠后可手动打开 Google Play 官方结算页，不会自动确认购买，也不需要 Frida 或电脑端脚本。

优惠能否使用取决于 Google Play 账号及地区，实际价格和续费条款请以结算页面显示的内容为准。

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

## 小米网络短信

在 Xposed 管理器中启用短信宿主，以及用于提供特权 SIM 身份的系统电话 / MIUI 短信作用域，重启对应进程后进入短信 App 设置中的「小米网络短信」。首次激活需要确认发送运营商短信；后续可独立刷新网络短信凭据，无需重复发送激活短信。

SIM 激活账号、Mixin 凭据和 Passport 验证状态保存在 MaiHoku UID 的私有 SharedPreferences 中，不使用应用层加密或 Android Keystore。短信宿主通过账号桥获取状态和操作结果，不接收账号密钥。模块不新增短信 App 或桌面入口，也不修改系统 APK。

Google Messages 与 AOSP/Lineage Messaging 使用各自的 DexKit 适配器。原生收件流程分别复用 Google 的 SmsReceivePipeline 与 AOSP 的 ReceiveSmsMessageAction；宿主升级后需要重新校验符号匹配和运行时兼容性。

## CI

- 推送到 `main`：自动构建并更新 `nightly` Release。
- 推送 tag：发布对应正式 Release。
- 推送到 `main` 且构建、`nightly` 发布成功后：将同一份正式 APK 同步到 [Xposed Module Repository](https://github.com/Xposed-Modules-Repo/io.github.xiaotong6666.maihoku)，版本标签为 `VersionCode-VersionName`。
- 修改 `README*.md`：自动同步至 Xposed 模块仓库；`SUMMARY`、`SCOPE` 和 `SOURCE_URL` 等元数据由模块仓库独立维护。

# Credits

[EzXHelper](https://github.com/KyuubiRan/EzXHelper)  
[DexKit](https://github.com/LuckyPray/DexKit)  
[libxposed API](https://github.com/libxposed/api)  
