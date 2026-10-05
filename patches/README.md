# patches/ —— 只存在于 WSL 编译树里的上游改动

## 为什么需要这个目录

这个仓库里的大部分文件都能一对一同步进编译树（`app/` → `packages/apps/AnzhiOS/`，
`device/bluejay/device.mk` → `device/anzhi/bluejay/device.mk` 等）。但有**三处改动落在上游项目自己的文件里**，
仓库根本没有对应文件可以放——不记下来，别人拿到这份仓库就编不出同一张 ROM。
这里就是那份记录。首次抓取 2026-10-05 03:28，SystemUI 那枚 15:27 补（来源 `<user>@AnzhiOS:/home/<user>/lineageos`）。

抓取命令（只读树）：

```bash
git -C frameworks/base diff -- core/res/res/values/config.xml
git -C frameworks/base diff -- packages/SystemUI/src/com/android/systemui/keyguard/ui/view/KeyguardIndicationArea.kt
git -C device/google/bluejay diff
git -C device/google/bluejay ls-files --others --exclude-standard
```

## 内容

| 文件 | 对应树内项目 | 改了什么 |
|---|---|---|
| `frameworks_base__core_res_config_xml.patch` | `frameworks/base` | `core/res/res/values/config.xml:5502` 的 `config_defaultAssistantAccessComponent`：改成**只留安知一个组件**（多组件串时 NMS 按 hashCode 升序取第一个，配置顺序决定不了胜负）。这是通知助理自动绑定唯一生效的通道（详见 `SYSTEM_ENTRIES_PLAN.md` §一 row #2） |
| `frameworks_base__systemui_keyguard_indication_area.patch` | `frameworks/base` | `packages/SystemUI/.../keyguard/ui/view/KeyguardIndicationArea.kt`：在锁屏提示区下方加一条**只读**的安知文字条（`enzosphere / welcome home · 欢迎回家`，不吃触摸、不参与上滑与密码面板手势），并在 `init` 打一条 `AnzhiKeyguard` 日志用来判定这条装配路径是否真在跑。选这个文件的原因：`DefaultIndicationAreaSection.addViews()` 和 `KeyguardViewConfigurator.initializeViews()` **两条装配路径都 new 同一个类**，改一处两边都覆盖 |
| `device_google_bluejay.patch` | `device/google/bluejay` | 三处胶水（下面逐条） |
| `extras/BoardConfig_anzhi.mk` | `device/google/bluejay/BoardConfig_anzhi.mk` | 未纳入 git 的新文件，被 `bluejay/BoardConfig.mk` 里那条 `include` 拉进来 |
| `untracked_list.txt` | — | 树内 `device/google/bluejay` 下所有未跟踪文件清单 |

`device/google/bluejay` 那三处胶水：

1. `lineage_bluejay.mk` 末尾加 `$(call inherit-product, device/anzhi/bluejay/device.mk)`
   —— 仓库里那份 `device/bluejay/device.mk` 就是靠这一行才进产品的，没有它整份 device.mk 是死文件。
2. `bluejay/BoardConfig.mk`：把 `include $(VENDOR_PATH)/BoardConfigVendor.mk` 改成
   `-include vendor/google/bluejay/BoardConfigVendor.mk`（缺 blob 时不再直接报错），
   再加 `include device/google/bluejay/BoardConfig_anzhi.mk`。
3. `aosp_bluejay.mk`：**只有版权头重排 + 末尾一行悬空注释**（`# Include Anzhi OS device overlay`，
   后面没有跟任何 include，是早期试错留下的），没有功能改动。这条我如实标出来，别当有效改动读。

## 树内还有两处未跟踪文件，仓库里已有对应物，但**其中一处是死代码**

- `device/google/bluejay/overlay/frameworks/base/core/res/res/values/config.xml`
  —— 树里这份**没有定义** `config_defaultAssistantAccessComponent`（实查 grep 计数 0），和仓库那份 `device/bluejay/overlay/...` 只是路径像。
  **仓库那份 overlay 才是生效路径**（10-05 翻案，我此前写的"静默失效"是错的、取证面太窄）：
  `device/anzhi/bluejay/device.mk:41 DEVICE_PACKAGE_OVERLAYS += device/anzhi/bluejay/overlay` → soong 编成静态 RRO
  `/vendor/overlay/framework-res__lineage_bluejay__auto_generated_rro_vendor.apk`（`targetPackage=android`、`isStatic=true`），
  运行时**盖住** `frameworks_base` 那份补丁改的上游值。⇒ 应用补丁的顺序要注意：
  两份现在同值（都是只留安知一个组件），但**真正起作用的是 overlay**；改 overlay 要 `m vendorimage` + 刷 vendor 才进手机。
- `device/google/bluejay/sepolicy/file_contexts` —— 对应仓库的 `device/bluejay/sepolicy/file_contexts`。

## 怎么应用

```bash
cd <lineageos 树根>
git -C frameworks/base      apply <repo>/patches/frameworks_base__core_res_config_xml.patch
git -C frameworks/base      apply <repo>/patches/frameworks_base__systemui_keyguard_indication_area.patch
git -C device/google/bluejay apply <repo>/patches/device_google_bluejay.patch
cp <repo>/patches/extras/BoardConfig_anzhi.mk device/google/bluejay/
```

前提：`lineage-23.2` / AOSP BP4A。行号会随上游更新漂移，打不上时按资源名
`config_defaultAssistantAccessComponent` 和上面「改了什么」逐条手改，不要硬 fuzz。
