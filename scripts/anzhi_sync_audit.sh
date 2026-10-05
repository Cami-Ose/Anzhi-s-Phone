#!/bin/bash
# =============================================================================
# 仓库 ↔ WSL 树 逐文件 md5 对账（只读，不写树、不建目录、不删任何东西）
#
# 为什么要有它：同步清单一旦写死，漏一个文件就是一整轮编译白跑（一个 build ≈ 1~2 小时）。
# 这个脚本不靠清单，直接把仓库里"树里应该有对应文件"的每一个文件都比一遍，
# 三个方向的差都会报出来：
#   DIFFERS          仓库改了、树里还是旧的     → 需要同步
#   MISSING-IN-TREE  仓库有、树里根本没有       → 需要新建（先确认是不是漏了映射）
#   REPO-MISSING     树里有、仓库没有           → 开源仓库缺构建输入，最阴的一种
#
# 用法（WSL，树在 /home/<user>/lineageos）：
#   bash anzhi_sync_audit.sh [仓库根] [树根]
# 输出：<local-path> + 终端摘要（要换地方就设 AUDIT_OUT）
# =============================================================================
set -u
# ⚠️ 默认值里那个撇号必须待在**内层双引号**里：写成 R="${1:-<repo-root>}" 时，
# ${...} 内部会重新开一层引号解析，那个 ' 会一路吞到文件里下一个单引号，bash 报
# "unexpected EOF while looking for matching ''"（今晚踩过，报错行还指向几百行之外）。
R=${1:-"<repo-root>"}
T="${2:-/home/<user>/lineageos}"
# 报告固定落 <local-path> /tmp 里跑也能在 Windows 侧看到
OUT="${AUDIT_OUT:-<local-scratch>"
: > "$OUT"

# 仓库相对路径 → 树相对路径
map() {
  case "$1" in
    app/Android.bp)          echo "packages/apps/AnzhiOS/Android.bp" ;;
    app/proguard-rules.pro)  echo "packages/apps/AnzhiOS/proguard-rules.pro" ;;
    app/libs/Android.bp)     echo "packages/apps/AnzhiOS/libs/Android.bp" ;;
    app/src/main/*)          echo "packages/apps/AnzhiOS/${1#app/}" ;;
    app/src/test/*)          echo "packages/apps/AnzhiOS/${1#app/}" ;;
    sepolicy/*)              echo "vendor/anzhi/sepolicy/${1#sepolicy/}" ;;
    vendor/anzhi/*)          echo "$1" ;;
    device/bluejay/device.mk) echo "device/anzhi/bluejay/device.mk" ;;
    *)                       echo "" ;;
  esac
}

[ -d "$R/app" ] || { echo "仓库不存在：$R" | tee -a "$OUT"; exit 9; }
[ -d "$T/packages/apps/AnzhiOS" ] || { echo "树不存在：$T" | tee -a "$OUT"; exit 9; }

cd "$R" || exit 9
find app sepolicy vendor/anzhi device/bluejay -type f 2>/dev/null | LC_ALL=C sort > /tmp/_audit_list.txt

same=0; differ=0; missing=0; nomap=0
: > /tmp/_audit_differs.txt; : > /tmp/_audit_missing.txt

# 注：取 md5 封成函数 h 单独调用。不要把它写在 [ A = B ] 里 ——
# bash 5.3 对"测试表达式里嵌套命令替换再套单引号"这种写法会直接 syntax error（今晚踩过）。
h() { md5sum "$1" 2>/dev/null | cut -d' ' -f1; }

while IFS= read -r f; do
  t=$(map "$f")
  if [ -z "$t" ]; then nomap=$((nomap+1)); echo "NO-MAPPING        $f" >> "$OUT"; continue; fi
  if [ ! -f "$T/$t" ]; then
    missing=$((missing+1)); echo "$f  ->  $t" >> /tmp/_audit_missing.txt
    echo "MISSING-IN-TREE   $f  ->  $t" >> "$OUT"; continue
  fi
  if [ "$(h "$f")" = "$(h "$T/$t")" ]; then
    same=$((same+1))
  else
    differ=$((differ+1)); echo "$f  ->  $t" >> /tmp/_audit_differs.txt
    echo "DIFFERS           $f  ->  $t" >> "$OUT"
  fi
done < /tmp/_audit_list.txt

echo "VERDICT same=$same differ=$differ missing-in-tree=$missing no-mapping=$nomap" >> "$OUT"

# 反方向：树里的安知文件，仓库有没有
revmiss=0
cd "$T" || exit 9
while IFS= read -r t; do
  case "$t" in
    packages/apps/AnzhiOS/Android.bp)      r="app/Android.bp" ;;
    packages/apps/AnzhiOS/libs/Android.bp) r="app/libs/Android.bp" ;;
    packages/apps/AnzhiOS/libs/*)          r="" ;;   # jar 是三方件，单独说
    packages/apps/AnzhiOS/*)               r="app/${t#packages/apps/AnzhiOS/}" ;;
    vendor/anzhi/sepolicy/*)               r="sepolicy/${t#vendor/anzhi/sepolicy/}" ;;
    vendor/anzhi/*)                        r="$t" ;;
    *)                                     r="" ;;
  esac
  [ -n "$r" ] && [ ! -f "$R/$r" ] && { revmiss=$((revmiss+1)); echo "REPO-MISSING      $t  <-  $r" >> "$OUT"; }
done < <(find packages/apps/AnzhiOS vendor/anzhi -type f 2>/dev/null | LC_ALL=C sort)

# 树里独有、但**确实不该进仓库**的两份（不是漏拷，别去追）：
#   vendor/anzhi/Android.mk  —— 728 字节全是注释、零条构建规则的占位文件
#   vendor/anzhi/default-permissions/anzhi_assistant_grant.xml —— 死文件，device.mk 里没有任何引用
echo "上面 REPO-MISSING 里这两份是已知的、不参与构建的树内残留，不是缺漏：" >> "$OUT"
echo "  vendor/anzhi/Android.mk / vendor/anzhi/default-permissions/anzhi_assistant_grant.xml" >> "$OUT"

{
  echo "REPO-MISSING-COUNT $revmiss"
  echo
  echo "已知的两个非源码构建输入（不在对账口径里，因为它们本来就不该进 git）："
  echo "  - packages/apps/AnzhiOS/libs/okhttp-4.12.0.jar  789531 B  md5 6acba053af88fed87e710c6c29911d7c（Apache-2.0，Maven 可取）"
  echo "  - scripts/build/anzhi_android.bp 是 6-30 的 android_app_import 残留，真配方是 app/Android.bp，别用那份 stub"
} >> "$OUT"

echo "---- differ ----"; cat /tmp/_audit_differs.txt 2>/dev/null
echo "---- missing-in-tree ----"; cat /tmp/_audit_missing.txt 2>/dev/null
echo "---- 全文见 $OUT ----"
tail -3 "$OUT"
