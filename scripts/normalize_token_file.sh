#!/usr/bin/env bash
# DEPRECATED 2026-10-03: ROM no longer bakes the token; nothing reads this file anymore. The token is now typed in-app (chat sidebar -> VPS settings) and stored in app-private prefs. Keep only if you want a local copy; safe to delete.
# 把令牌文件统一成 ASCII/LF（PowerShell 5.1 的 > 会写成 UTF-16LE，WSL 侧 grep 读不到）
# 只报长度与编码，绝不打印正文。
f=${1:-<user-home>
[ -f "$f" ] || { echo "MISSING $f"; exit 1; }

bom=$(head -c 2 "$f" | od -An -tx1 | tr -d ' \n')
echo "before_bom=$bom"

t=$(mktemp)
trap 'rm -f "$t"' EXIT

if [ "$bom" = "fffe" ] || [ "$bom" = "feff" ]; then
    iconv -f UTF-16LE -t ASCII//TRANSLIT "$f" > "$t" || { echo "ICONV_FAILED"; exit 1; }
else
    tr -d '\r' < "$f" > "$t"
fi

if ! grep -q '^ANZHI_API_TOKEN=' "$t"; then
    echo "NO_ANCHOR_AFTER_CONVERT"
    exit 1
fi

v=$(grep -m1 '^ANZHI_API_TOKEN=' "$t" | cut -d= -f2- | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
echo "value_len=${#v}"
printf 'ANZHI_API_TOKEN=%s\n' "$v" > "$f" || { echo "WRITE_FAILED"; exit 1; }

echo "after_bom=$(head -c 2 "$f" | od -An -tx1 | tr -d ' \n')"
echo "anchor=$(grep -c '^ANZHI_API_TOKEN=' "$f")"
echo "OK"
