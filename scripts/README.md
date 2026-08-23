# scripts/ — 安知手机 开发辅助脚本

## collect_avc — SELinux AVC 日志收集

SELinux 开发阶段设为 **Permissive** 模式——不阻止操作，但记录所有违规日志。
用此脚本持续收集 `avc: denied` 日志，为每个新模块同步补齐 `.te` 规则。

### WSL / Linux

```bash
chmod +x collect_avc.sh
./collect_avc.sh                    # 实时输出
./collect_avc.sh -o avc.log &       # 后台写入文件
./collect_avc.sh -c                 # 统计当前 denied 数量
./collect_avc.sh -p                 # 生成 .te 规则建议
```

### Windows PowerShell

```powershell
.\scripts\collect_avc.ps1                        # 实时输出
Start-Job { .\scripts\collect_avc.ps1 -OutputFile avc.log }  # 后台
.\scripts\collect_avc.ps1 -CountOnly              # 统计
.\scripts\collect_avc.ps1 -ParseOnly              # 生成 .te 建议
```

---

## SELinux 工作流

```
1. 编译 ROM 时 SELinux 设为 Permissive
2. 刷机 → 开机 → 运行 collect_avc 脚本（后台常驻）
3. 每开发完一个新模块 → ./collect_avc.sh -p 生成规则建议
4. 把生成的 allow 规则追加到 sepolicy/anzhi_service.te
5. 全模块开发完成后 → SELinux 切回 Enforcing → 编译最终 ROM
```

BUILD.md 参考：Step 2、陷阱 4

---

## Data Saver 豁免（Step 2）

Android Data Saver（流量节省程序）是独立于 Doze 的网络封杀策略。
即使 Doze 白名单已配置，Data Saver 仍会掐断后台 TCP 连接。
必须在刷机后执行以下命令，安知 WebSocket 才能穿透流量节省墙：

```bash
adb shell cmd netpolicy add restrict-background-whitelist com.anzhi.os
```

验证是否生效：

```bash
adb shell cmd netpolicy list restrict-background-whitelist
```

BUILD.md 参考：陷阱 13（Deep Doze）、陷阱 19（重连风暴）

---

## build_rom — ROM 全编译脚本

Step 14 统一编译入口。封装：源码部署、SELinux 模式切换、AOSP 编译、刷机验证清单。

### 用法

```bash
chmod +x scripts/build_rom.sh

./scripts/build_rom.sh              # 全编译 ROM + Enforcing（需 ≥16GB 内存）
./scripts/build_rom.sh --anzhi-only # 仅编译 AnzhiOS APK（≥8GB 内存即可）
./scripts/build_rom.sh --verify     # 仅验证编译环境，不编译
./scripts/build_rom.sh --dev        # 开发模式：Permissive + 仅编译 AnzhiOS
```

### 刷机后验证清单

脚本执行完会自动打印，也可手动逐条跑：

```bash
# 1. SELinux 模式
adb shell getenforce
# 期望：Enforcing

# 2. 安知服务状态
adb shell dumpsys activity services com.anzhi.os

# 3. Data Saver 豁免
adb shell cmd netpolicy list restrict-background-whitelist | grep anzhi

# 4. Doze 白名单
adb shell dumpsys deviceidle whitelist | grep anzhi

# 5. AVC 日志（开发阶段）
adb logcat -d -s auditd | grep -iE "avc.*anzhi|avc.*com\.anzhi"
```

BUILD.md 参考：Step 14（SELinux 收紧 + AOSP 构建）

---

## 其他常用命令

### 检查当前 SELinux 模式

```bash
adb shell getenforce
# Permissive = 开发模式（记录但不阻止）
# Enforcing  = 生产模式（阻止违规操作）
```

### 临时切换为 Permissive（不刷机）

```bash
adb root
adb shell setenforce 0
```

### 查看安知相关 avc 日志（一次性）

```bash
adb logcat -d -s auditd | grep -iE "avc.*anzhi|avc.*com\.anzhi"
```
