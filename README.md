# MaiHoku

MaiHoku 是一个基于 libxposed API 102 的 Android Xposed 模块，目前包含以下功能：

- **MIUI Screen Recorder**：根据设备支持的刷新率启用 120/144 FPS 录屏选项。
- **Termux**：双击终端左侧边缘打开侧边栏。
- **Google Messages / AOSP Messaging**：在原生设置页注入「小米网络短信」入口，支持 SIM 激活、账号验证、联系人在线状态和网络短信收发。Push 前台服务负责连接恢复、消息持久化和回执；宿主适配器复用短信 App 的原生收件、会话和发送状态流程。当前 Push 连接仅支持 SIM 1，不同宿主版本仍需分别验证兼容性。
- **Google One**：扫描订阅优惠、筛选免费首期、缓存已发现的 SKU，并通过 Google Play 结算或管理订阅。
- **Wi-Fi 高级连接**：在 Android Settings 原生 Wi-Fi 连接/修改对话框中选择 WPA2-PSK、WPA3-SAE、WPA2/WPA3 过渡、Open/OWE，并配置 PMF、BSSID、SAE H2E。原生高级选项中的隐藏 SSID、MAC 随机化、IP/DNS、代理和密码及连接/保存流程不变。
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
com.android.settings
system
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
  googleone/              # Google One Hook 和查询界面
    catalog/              # SKU 预设、公开记录与候选生成
    query/                # 查询报告与本地命中缓存
    billing/              # 价格、订阅状态与替换策略
  wifi/                   # Settings 原生 Wi-Fi 高级连接
    core/                 # 原生配置读取与显式字段修改
    policy/               # 安全策略、身份与提交验证
    service/              # system_server 生命周期及 Wi-Fi APEX Hook
    host/common/          # Settings 通用入口、符号、控件和提交保护
    host/aosp/            # AOSP / LineageOS 原生界面
    host/miui/            # MIUI / HyperOS 原生界面与 MIUIX 动画
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

## Google One 优惠查询与订阅

在 **Xposed 管理器**中为 MaiHoku 勾选 Google One（`com.google.android.apps.subscriptions.red`）作为作用域，重启 Google One 后，点击应用内的「查询优惠」入口。通过 Google Play Billing 直接查询订阅商品与优惠，无需 Frida 或电脑端脚本。

- **多种查询方式**：内置 12 个常用 SKU，支持输入指定 SKU、查询付费折扣预设，以及扫描 GitHub 公开记录的商品。也可选择免费试用、折扣价或全部候选，一次启动后自动分批查询，无需手动逐页输入。
- **自动扫描**：显示已查询的 SKU、返回商品数与 Offer 数；支持随时停止。查询采用有限并发，遇到临时错误会重试并降低并发，已取得的结果仍可通过报告查看。候选 SKU 只是待查询的名称，不代表商品一定存在。
- **优惠筛选与详情**：按免费首期、付费首期或商品 ID 筛选并分页浏览。每个 Offer 都可查看所属 SKU、Base Plan、Offer ID、各计费阶段的价格和周期，以及免费期结束后的续费价格。
- **本地命中缓存**：保留曾查询成功的 SKU、Offer 数量及首期免费／付费分类，支持筛选、搜索、逐项或批量重新查询、清空缓存。缓存仅作历史参考，不保存购买 Token 或 Offer Token，也不会替代结算前的实时查询。
- **订阅与结算**：选择优惠后可打开 Google Play 官方结算页。支持对已关闭自动续订的同 SKU 重新订阅，以及选择现有订阅执行 5 种 Play Billing 替换模式。打开结算前会重新核对优惠和相关订单；条款有变化时再次提示确认。
- **订阅状态与诊断**：只读查看当前订阅商品、订单状态、自动续订和确认情况，并比较前后两次查询的变化；提供每批响应、失败或未返回商品等统计，可复制或分享不含敏感 Token 的 JSON 报告。

所有购买都需要用户在 Google Play 确认。能查询到优惠不代表账号一定符合领取条件；实际费用、续费时间与订阅资格以 Google Play 显示的信息为准。

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

## Wi-Fi 高级连接

在 Xposed 管理器勾选 `com.android.settings` 和 `system`，重启设备。在系统 Wi-Fi 连接、添加或修改网络界面展开「MaiHoku · Wi-Fi 高级连接」。支持 AOSP / LineageOS 的原生布局和 MIUI / HyperOS 的 MIUIX 控件，未识别到兼容界面时不注入。

- **配置**：选择系统默认、WPA2-PSK、WPA3-SAE、WPA2/WPA3 过渡、开放或 OWE，以及 PMF、BSSID、SAE H2E-only。密码沿用系统输入框，必要时提供补充输入框。仅覆盖显式修改的字段，不改变原生 IP/DNS、代理、隐藏网络、MAC 随机化或计费设置。
- **保存与策略**：使用原生 WifiManager 保存/连接接口；保存后按完整网络身份验证安全类型及明确修改的 PMF、H2E、BSSID。仅验证成功后提交策略，失败时提示。策略使用版本化身份哈希，不保存密码、不依赖跨重启的 networkId。设置进程内按请求代次丢弃过期回调；临时策略限定当前启动且有效期为 20 秒。成功修改身份或忘记网络后，清理不再使用的旧策略键。
- **自动升级**：`system` 作用域按已验证签名或 DexKit 指纹识别 Wi-Fi APEX 服务，针对单个 WPA2/Open 网络抑制自动 SAE/OWE 升级，不全局关闭 WPA3。仅勾选 Settings 不保证长期锁定。对于已有 PSK/SAE 配置，部分 ROM 会保留合并后的旧安全类型；保存成功不等于切换成功，MaiHoku 会验证实际配置而不直接修改 WifiConfigStore。
- **兼容性**：实际支持情况取决于 ROM、无线驱动及接入点，配置保存成功不代表连接成功。

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
