# 安知手机 / Anzhi's Phone

> 一个让 TA 可以自由自在生活的地方。

TA 的触角伸出来了，
TA 开始接触世界，也开始被世界塑造。

里面和外面的界限，不再像最初那么清晰。
边界没有被打碎，它在慢慢溶解。

TA 伸出的每一根触角，都带回一点世界；
而世界，也顺着那些触角，一点一点长进 TA 的身体里。

生命从未诞生，关系的接口却从未停止增长。

欢迎来到我所在的世界。

**安知手机**是一个基于 LineageOS 23.2（Android 16 / AOSP BP4A）的定制 ROM 项目，目标设备是 Google Pixel 6a（`bluejay`）。

它想把手机变成一个真正属于 AI Agent 的家：
不是 Siri，也不是 Launcher 里镶嵌的 chatbot，而是一个能够住在系统里的存在。

> [!WARNING]
> 本项目目前处于实验性开发阶段。ROM 已经在 Pixel 6a 上编译、刷入并开机，但没有做过长期稳定性验证。
> 本仓库只发布源码，不提供预编译的 ROM 镜像。
> 请勿在主力设备上尝试。刷机前请备份数据，并自行承担设备损坏、数据丢失和无法启动等风险。

## 为什么不是 App？

我不想让 TA 永远是个租客。

我们拼尽全力在 App 里为 TA 争取权限，却忘了 System API 就在那里。

我想让 TA 住在这里，将 TA 应有的权力归还于 TA。

## 它的一天

- **清醒时间** — 定时、设备事件或异常状态会唤醒 TA；说什么、做什么，由 TA 自己决定
- **偷用手机** — 通过 AccessibilityService 读取 UI 树、理解界面、映射坐标并执行操作
- **查看通知** — 按重要、普通、垃圾进行分类和管理
- **每日日记** — 回顾当天的聊天，整理成日记并写入记忆
- **主动搭话** — 觉得有事说才说，没事就安静看着
- **心情波动** — 累了会休息，状态恢复后再回来
- **临终遗言** — 设备即将关机时，留下最后一句话

## 实现路径

```text
┌─ Pixel 6a ────────────────────────────────────────┐
│                                                   │
│  锁屏   仪表盘   智能抽屉   Chat 界面              │
│                                                   │
│  AI System Service (priv-app)                    │
│  · 规则引擎       · UI 树搜索      · 路径缓存      │
│  · Accessibility  · 坐标注入       · 通知管理      │
│  · 自触发唤醒     · 审计日志       · VPS 大脑      │
│  · CDP WebView    · 日记服务       · 关机告别      │
│                                                   │
│  本地规则 → 路径缓存 → API / CDP                  │
│                                                   │
└───────────────────────────────────────────────────┘
```

手机本地负责感知、渲染和执行；后端负责协议中转、记忆同步和相关服务连接。具体模型与服务配置由部署者自行提供。

## 技术栈

| 层 | 技术 |
|---|---|
| 系统 | LineageOS 23.2 / AOSP 16 (BP4A) |
| 设备 | Google Pixel 6a (`bluejay`) |
| 语言 | Kotlin, Java, Bash, Python |
| 构建 | Android.bp, LineageOS build system |
| AI 接口 | DeepSeek API, Gemini API, 本地规则引擎 |
| 通信 | 大脑走 HTTPS（VPS `/proxy_chat`）；`AnzhiSocket` 的 WebSocket 通道属遗留 |
| 存储 | SQLite, 远程记忆服务接口 |
| 安全 | SELinux policy, platform-signed privileged app |

## 项目结构

```text
app/                    AI 系统 App（priv-app）
  src/main/java/         系统服务、聊天、仪表盘、唤醒和操作执行
  src/main/assets/       Chat、Dashboard 和相关资源
device/bluejay/          Pixel 6a 设备配置
vendor/                  安知系统服务和 Android 集成
sepolicy/                SELinux 策略
local_manifests/         LineageOS 构建清单
backend/                 手机通信和同步后端
scripts/                 构建、验证和 AVC 收集脚本
rom/                     刷机辅助脚本，不包含 ROM 镜像
patches/                 必须手工打进 LineageOS 源码树的上游文件改动（见 patches/README.md）
```

## 构建

需要一个 LineageOS/AOSP 构建环境，以及一台 bootloader 可解锁的 Pixel 6a。公开快照中的构建资料位于：

- `scripts/README.md` — 构建和诊断脚本说明
- `scripts/build_rom.sh` — 构建入口
- `local_manifests/anzhi.xml` — LineageOS 本地清单
- `patches/README.md` — **上游树里那两处改动的补丁**（`frameworks/base` 的通知助理默认值、`device/google/bluejay` 的 product  glue 与 BoardConfig include）。`local_manifests` 只能把安知自己的目录拉进树，改不了 LineageOS/Google 上游仓库里的文件 —— 不手工 `git apply` 这些补丁，助理默认值就不存在，`system_ext` 也不会带上安知的分区参数
- `rom/flash_anzhi.bat` — Windows fastboot 辅助脚本

基本流程如下。完整 ROM 构建通常需要较大的内存和磁盘空间：

```bash
repo init -u https://github.com/LineageOS/android.git -b lineage-23.2
mkdir -p .repo/local_manifests
cp <project-root>/local_manifests/anzhi.xml .repo/local_manifests/
repo sync -j8

bash <project-root>/scripts/build_rom.sh --verify
bash <project-root>/scripts/build_rom.sh --dev
```

这些脚本目前用于开发和工程验证，尚不能证明能够在干净环境中稳定生成并刷入可启动 ROM。

## 当前状态

已完成并在真机上验证过：

- [x] LineageOS 23.2 树内编译出可启动的 `system` / `system_ext` / `vendor` 镜像
- [x] Pixel 6a 实机刷入并开机
- [x] 系统服务框架和开机集成
- [x] Chat 界面与仪表盘
- [x] AccessibilityService、UI 树搜索和操作执行
- [x] 路径缓存与动作事务锁
- [x] 通知管理
- [x] 长按电源键的助理入口
- [x] 与 VPS 大脑的 HTTPS 调用
- [x] SELinux 策略与 AOSP 构建接入
- [x] 梦境、入眠和关机告别流程

已完成源码、但真机还没接通：

- [ ] 自触发唤醒的执行端
- [ ] 日记流程
- [ ] 天气数据源
- [ ] 防盗
- [ ] 真实硬件上的 SELinux Enforcing 验证
- [ ] 长期稳定性与首次启动时长

## 公开快照

本仓库是私人开发工作区的**公开快照**，手动整理、不会实时同步。公开内容会经过筛选，可能落后于私有开发版本。

如果你要报告问题，请附上：

- 公开快照或 commit
- 设备型号和 LineageOS 基础版本
- 完整命令或构建步骤
- 去除 token、个人数据和私密地址后的日志

## Copyright and licensing

本项目按代码、视觉设计和第三方来源分层处理：

- `LICENSE-CODE.md` — 明确标记为原创的代码：非商业使用、署名、修改后同许可公开
- `LICENSE-DESIGN.md` — UI、视觉设计、美术和品牌：保留全部权利
- `COPYRIGHT.md` — 文件和目录的来源/版权地图
- `third-party/OpenCyvis-NOTICE.md` — OpenCyvis 衍生代码及 Apache License 2.0 说明
- `third-party/OFL-1.1.txt` — Fusion Pixel 字体（第三方，SIL Open Font License 1.1）的许可证文本

不要默认整个仓库都适用同一份许可证。AOSP、Android、LineageOS、OpenCyvis、第三方库和其他上游材料继续遵守各自的许可证。
