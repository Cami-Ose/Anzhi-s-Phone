#!/usr/bin/env bash
O=<local-scratch>
B=/home/<user>/lineageos/frameworks/base/packages/SystemUI
cd "$B" || exit 1
{
  echo "=== 0. 自检：树还在不在、当前编译进度 ==="
  ls -d plugin plugin_core shared src 2>/dev/null
  echo
  echo "=== 1. plugin_core 全部文件 ==="
  find plugin_core -type f | head -40
  echo
  echo "=== 2. 谁提到 PluginRepo / EnabledActionDao（文本级） ==="
  grep -rl "PluginRepo\|EnabledActionDao" plugin plugin_core shared src 2>/dev/null | head -10
  echo
  echo "=== 3. 谁调用 queryIntentServices（整个 SystemUI，含子目录） ==="
  grep -rn "queryIntentServices" plugin plugin_core shared src 2>/dev/null | head -10
  echo
  echo "=== 4. PLUGIN 权限定义点 ==="
  grep -rn "systemui.permission.PLUGIN" . --include=AndroidManifest.xml 2>/dev/null | head -10
  echo
  echo "=== 5. PluginActionManagerImpl 里的连接/校验 ==="
  grep -n "permission\|Permission\|signatures\|bindService\|version" plugin_core/src/com/android/systemui/plugins/shared/PluginActionManagerImpl.java 2>/dev/null | head -20
} > "$O" 2>&1
wc -l "$O"
