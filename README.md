# MaiHoku

MaiHoku 是一个基于 libxposed API 102 的 Android Xposed 模块，将多个 Hook 合并到同一个 APK：

- `com.miui.screenrecorder`：在支持 120 Hz 的设备上启用屏幕录制 120 FPS 选项。
- `com.termux`：双击终端左侧边缘时打开侧边栏。
- `org.telegram.messenger` / `org.telegram.messenger.web`：Telegram 官方客户端增强框架。动态符号采用 DexKit，反射辅助使用 EzXHelper core，真正的 Hook 统一由 libxposed API 102 执行；首个功能为可配置的 MTProto API identity override。

模块使用静态作用域，只会加载到上述目标包。统一入口会根据当前包名初始化对应 Hook，各功能互不影响。

## Telegram API identity

Telegram API identity 不写死在源码中。构建时由 Gradle 从项目根目录、已被 `.gitignore` 忽略的 `local.properties` 读取：

```properties
telegram.api_id=<api-id>
telegram.api_hash=<api-hash>
```

`telegram.api_id` 与 `telegram.api_hash` 必须同时提供。两者都未设置时，构建仍可正常完成，但生成的默认 identity 为空，运行时 `ApiIdentityFeature` 默认不启用。

Hook 进程仍通过 libxposed API 102 remote preferences 读取 `telegram` 组，因此可以覆盖默认值：

- `identity.enabled`：`Boolean`，默认值取决于构建时是否提供了完整 identity。
- `identity.api_id`：`Int`，默认来自构建时 `local.properties` 的 `telegram.api_id`。
- `identity.api_hash`：`String`，默认来自构建时 `local.properties` 的 `telegram.api_hash`。
- `sms.profile_enabled`：`Boolean`，默认 `true`。启用登录/SMS 兼容策略：关闭 Firebase/Play Integrity SMS 能力、关闭官方-only Passkey，并在 SMS Fee 页面优先使用 Telegram invoice billing。
- `ui.mutual_contact_enabled`：`Boolean`，默认 `true`。在联系人列表中对 `TLRPC.User.mutual_contact=true` 的用户显示 `⇄` 双向联系人标记。
- `ui.profile_identity_enabled`：`Boolean`，默认 `true`。在 ProfileActivity 头像/状态区域显示 `ID: <userId>, DC: <dc>`；长按可复制 ID、DC 或两者。为了兼容前一版，也会把旧的 `ui.user_card_dc_enabled` 作为默认回退值读取。

构建时提供 identity 后，官方 Telegram 进程进入 `onPackageReady()` 会使用 Gradle 生成的 `BuildConfig.TELEGRAM_API_ID/TELEGRAM_API_HASH` 覆盖 `BuildVars.APP_ID/APP_HASH`。模块先验证字段结构，再 Hook `BuildVars.<clinit>` 并覆盖值；同时对已完成类初始化的场景执行一次立即写入。`TL_auth_sendCode` 与 `TL_auth_exportLoginToken` 在序列化前会执行只读 audit，日志只输出 API hash 的短 SHA-256 指纹，不输出完整 hash。

这只是不把 API identity 提交到 Git 仓库；如果把值编译进 APK，它最终仍会存在于生成的 DEX/BuildConfig 中，不能把这种构建注入当作加密或秘密存储。

SMS authentication profile 不会吞掉或伪造 `auth.sentCodePaymentRequired`。模块会把 `BuildVars.SAFETYNET_KEY` 置空、`SUPPORTS_PASSKEYS` 置为 `false`，并在 `TL_codeSettings` 序列化前强制 `allow_firebase=false`。如果服务端仍返回 `TL_auth_sentCodeTypeFirebaseSms`，则在其反序列化完成后标记 `verifiedFirebase=true`，使官端跳过 Play Integrity/SafetyNet 中间请求，继续走后续普通 SMS 页面路径。SMS Fee 页通过 DexKit 语义定位，仅在该页面调用 `BuildVars.useInvoiceBilling()` 时返回 `true`，不会全局改变其他 Premium/Stars 等支付页面的 billing 策略。

联系人双向标记按联系人列表的目标覆盖范围实现：仅在 `ContactsAdapter` 创建联系人 `UserCell` 时启用双向联系人标记，MaiHoku 不修改官端构造签名，而是用 DexKit 找到官端 `UserCell`，只对联系人列表使用的 `58/1` cell 注入同等语义的 `⇄` 标记。用户 ID/DC 则对齐目标 `ProfileActivity` 顶部身份信息展示方式：挂在官端现有状态文本下方，用户 DC 来自 `user.photo.dc_id`，自己的账号无头像 DC 时回退到当前连接的数据中心；长按显示复制菜单。

## 从旧模块迁移

MaiHoku 使用新的应用 ID `io.github.xiaotong6666.maihoku`。安装并启用 MaiHoku 后，请在 Xposed 管理器中禁用或卸载原来的 `sr120hook` 和 `TermuxHook`，避免同一个功能被重复 Hook。

## 构建

```bash
./gradlew assembleRelease
```

生成的 APK 位于 `app/build/outputs/apk/release/`。

构建环境：Compile SDK 36、Min SDK 26、Java 17、libxposed API 102。

默认从 Maven Central 获取 EzXHelper。如需调试 EzXHelper 本地源码，可将 `EZXHELPER_PATH` 指向其项目目录后再构建。
