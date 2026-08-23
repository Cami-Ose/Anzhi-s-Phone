#!/usr/bin/env python3
"""
Mock 手机客户端 —— 没设备时模拟手机连后端调试。

用法：
  # 交互模式
  python -m tests.mock_phone

  # 跑预设场景
  python -m tests.mock_phone --scenario demo
  python -m tests.mock_phone --scenario notification_spam
  python -m tests.mock_phone --scenario wake_trigger

后端需要先启动：
  python app.py
"""

import asyncio
import json
import sys
import time
from pathlib import Path

# 把 backend 目录加到 path（从 tests/ 运行时）
sys.path.insert(0, str(Path(__file__).parent.parent))

import aiohttp

# ── 配置 ──
WS_URL = "ws://localhost:8080/anzhi?device=mock-pixel-6a"

# ── 预设场景 ──

SCENARIOS = {}


def scenario(name: str):
    """装饰器：注册一个场景。"""
    def deco(func):
        SCENARIOS[name] = func
        return func
    return deco


async def send(ws, msg_type: str, payload: dict = None):
    """发一条消息到后端。"""
    data = json.dumps({"type": msg_type, "payload": payload or {}})
    print(f"\n  📤 → {msg_type}: {json.dumps(payload, ensure_ascii=False)[:100]}")
    await ws.send_str(data)


# ═══════════════════════════════════════════════════════════
# 场景
# ═══════════════════════════════════════════════════════════

@scenario("demo")
async def scenario_demo(ws):
    """完整演示：通知 → 聊天 → UI 树 → 操作。"""
    print("\n═══ Demo 场景开始 ═══")

    # 1. 上报状态
    await send(ws, "status_update", {
        "foreground_app": "com.tencent.mm",
        "screen_on": True,
        "battery": 72,
        "charging": False,
        "storage_free_mb": 5000,
        "cpu_temp": 38.0,
        "ram_used_pct": 55,
        "background_processes": ["com.bilibili.app"],
        "silent_mode": False,
    })
    await asyncio.sleep(0.5)

    # 2. 收到一条微信通知
    await send(ws, "notification", {
        "id": 1,
        "app": "com.tencent.mm",
        "title": "张三",
        "text": "在吗？晚上一起吃饭",
    })
    await asyncio.sleep(1)

    # 3. 收到一条垃圾通知
    await send(ws, "notification", {
        "id": 2,
        "app": "com.taobao.taobao",
        "title": "限时秒杀",
        "text": "iPhone 20 只要 ¥999！点击抢购！",
    })
    await asyncio.sleep(0.5)

    # 4. Cami 说话
    await send(ws, "user_message", {"text": "帮我把微信的张三设为免打扰"})
    await asyncio.sleep(1)

    # 5. 后端应该请求 UI 树了
    await send(ws, "ui_tree", {
        "app": "com.tencent.mm",
        "nodes": [
            {"class": "android.widget.TextView", "text": "微信", "bounds": [200, 50, 500, 120], "clickable": False},
            {"class": "android.widget.Button", "text": "聊天", "bounds": [50, 200, 250, 300], "clickable": True},
            {"class": "android.widget.TextView", "text": "张三", "bounds": [80, 400, 400, 500], "clickable": True, "long_clickable": True},
            {"class": "android.widget.TextView", "text": "李四", "bounds": [80, 520, 400, 620], "clickable": True},
            {"class": "android.widget.Button", "text": "设置", "bounds": [900, 200, 1050, 300], "clickable": True},
            {"class": "android.widget.Button", "text": "发送", "bounds": [850, 1800, 1050, 1900], "clickable": True},
        ],
    })
    await asyncio.sleep(1)

    # 6. 操作完成
    await send(ws, "action_done", {"description": "已设置张三免打扰", "action_type": "tap"})
    await asyncio.sleep(0.5)

    print("\n═══ Demo 场景结束 ═══")


@scenario("notification_spam")
async def scenario_notification_spam(ws):
    """发送一堆通知，测试三级过滤。"""
    print("\n═══ 通知过滤场景 ═══")

    tests = [
        # (app, title, text, expected)
        ("phone", "妈妈", "到家了吗？", "important"),
        ("com.taobao.taobao", "限时秒杀", "全场 5 折，快来抢购", "spam"),
        ("com.tencent.mm", "群聊-摸鱼群", "@所有人 明天聚餐", "normal"),
        ("com.android.mms", "验证码", "您的验证码是 123456", "important"),
        ("calendar", "提醒", "15:00 开会", "important"),
        ("com.pinduoduo.pinduoduo", "红包雨", "恭喜获得 0.01 元红包", "spam"),
        ("com.tencent.mm", "物流通知", "您的快递已签收", "normal"),
        ("com.bilibili.app", "直播提醒", "你关注的主播开播了", "normal"),
    ]

    for i, (app, title, text, expected) in enumerate(tests):
        await send(ws, "notification", {
            "id": 100 + i,
            "app": app,
            "title": title,
            "text": text,
        })
        print(f"    期望: {expected}")
        await asyncio.sleep(0.3)

    print("\n═══ 通知过滤场景结束 ═══")


@scenario("wake_trigger")
async def scenario_wake_trigger(ws):
    """模拟低电量触发 bot 事件唤醒。"""
    print("\n═══ 唤醒触发场景 ═══")

    # 模拟低电量
    await send(ws, "status_update", {
        "foreground_app": "com.sina.weibo",
        "screen_on": True,
        "battery": 12,
        "charging": False,
        "storage_free_mb": 3000,
        "cpu_temp": 40.0,
        "ram_used_pct": 60,
        "background_processes": [],
        "silent_mode": False,
    })
    print("    期望: bot 触发 low_battery 事件唤醒")
    await asyncio.sleep(2)

    print("\n═══ 唤醒触发场景结束 ═══")


# ═══════════════════════════════════════════════════════════
# 交互模式
# ═══════════════════════════════════════════════════════════

HELP = """
命令:
  status                  — 发送状态更新
  notify <app> <title> <text> — 发送通知
  ui <app>                — 发送示例 UI 树
  chat <text>             — Cami 说话
  screenshot              — 发送假截图
  log <entry1>|<entry2>   — 发送 today_log
  app <package>           — 切换 App
  done <desc>             — 操作完成
  error <msg>             — 操作失败
  scenario <name>         — 跑预设场景
  scenarios               — 列出所有场景
  sleep <seconds>         — 等待
  quit                    — 退出
"""


async def interactive_mode(ws):
    """交互模式：读用户输入 → 发送消息。"""
    print(HELP)

    # 后台读消息
    async def read_msgs():
        try:
            async for msg in ws:
                if msg.type == aiohttp.WSMsgType.TEXT:
                    data = json.loads(msg.data)
                    print(f"\n  📥 ← {data['type']}: {json.dumps(data.get('payload', {}), ensure_ascii=False)[:150]}")
                    print("> ", end="", flush=True)
        except Exception:
            pass

    reader = asyncio.create_task(read_msgs())

    # 心跳
    async def heartbeat():
        while True:
            await asyncio.sleep(30)
            await send(ws, "heartbeat", {})

    hb = asyncio.create_task(heartbeat())

    # 主循环
    loop = asyncio.get_event_loop()

    try:
        while True:
            # 用 run_in_executor 避免阻塞事件循环
            line = await loop.run_in_executor(None, lambda: input("> "))
            line = line.strip()
            if not line:
                continue

            parts = line.split(maxsplit=1)
            cmd = parts[0].lower()
            arg = parts[1] if len(parts) > 1 else ""

            if cmd == "quit" or cmd == "exit":
                break

            elif cmd == "help":
                print(HELP)

            elif cmd == "status":
                await send(ws, "status_update", {
                    "foreground_app": "com.tencent.mm",
                    "screen_on": True,
                    "battery": 72,
                    "charging": False,
                    "storage_free_mb": 5000,
                    "cpu_temp": 38.0,
                    "ram_used_pct": 55,
                    "background_processes": [],
                    "silent_mode": False,
                })

            elif cmd == "notify":
                try:
                    a, t, tx = arg.split(maxsplit=2)
                except ValueError:
                    print("  格式: notify <app> <title> <text>")
                    continue
                await send(ws, "notification", {
                    "id": int(time.time() * 1000) % 10000,
                    "app": a, "title": t, "text": tx,
                })

            elif cmd == "ui":
                app_name = arg or "com.tencent.mm"
                await send(ws, "ui_tree", {
                    "app": app_name,
                    "nodes": [
                        {"class": "android.widget.TextView", "text": app_name, "bounds": [200, 50, 500, 120]},
                        {"class": "android.widget.Button", "text": "返回", "bounds": [30, 100, 120, 200], "clickable": True},
                        {"class": "android.widget.TextView", "text": "联系人", "bounds": [80, 400, 400, 500], "clickable": True},
                        {"class": "android.widget.TextView", "text": "设置", "bounds": [80, 520, 400, 620], "clickable": True},
                        {"class": "android.widget.EditText", "text": "搜索…", "bounds": [100, 700, 900, 800], "editable": True},
                        {"class": "android.widget.Button", "text": "发送", "bounds": [850, 1800, 1050, 1900], "clickable": True},
                    ],
                })

            elif cmd == "chat":
                if not arg:
                    print("  格式: chat <消息>")
                    continue
                await send(ws, "user_message", {"text": arg})

            elif cmd == "screenshot":
                # 发一个假的 1x1 像素 PNG 的 base64
                fake_png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
                await send(ws, "screenshot", {"image": fake_png})

            elif cmd == "log":
                entries = []
                for e in arg.split("|"):
                    entries.append({"time": "14:02", "desc": e.strip()})
                await send(ws, "today_log", {"entries": entries})

            elif cmd == "app":
                await send(ws, "app_opened", {"package": arg or "com.tencent.mm"})

            elif cmd == "done":
                await send(ws, "action_done", {"description": arg or "操作完成"})

            elif cmd == "error":
                await send(ws, "error", {"message": arg or "出错了"})

            elif cmd == "sleep":
                try:
                    secs = float(arg)
                except ValueError:
                    secs = 1.0
                print(f"  等待 {secs}s…")
                await asyncio.sleep(secs)

            elif cmd == "scenario":
                name = arg or "demo"
                if name in SCENARIOS:
                    await SCENARIOS[name](ws)
                else:
                    print(f"  未知场景: {name}")
                    print(f"  可用: {', '.join(SCENARIOS.keys())}")

            elif cmd == "scenarios":
                print(f"  可用场景: {', '.join(SCENARIOS.keys())}")

            else:
                print(f"  未知命令: {cmd}")

    finally:
        reader.cancel()
        hb.cancel()
        print("  已断开")


# ═══════════════════════════════════════════════════════════
# 入口
# ═══════════════════════════════════════════════════════════

async def main():
    scenario_name = None
    if "--scenario" in sys.argv:
        idx = sys.argv.index("--scenario")
        if idx + 1 < len(sys.argv):
            scenario_name = sys.argv[idx + 1]

    print(f"🔌 连后端: {WS_URL}")
    try:
        async with aiohttp.ClientSession() as session:
            async with session.ws_connect(WS_URL) as ws:
                print("✅ 已连接\n")

                if scenario_name:
                    if scenario_name in SCENARIOS:
                        await SCENARIOS[scenario_name](ws)
                    else:
                        print(f"未知场景: {scenario_name}")
                        print(f"可用: {', '.join(SCENARIOS.keys())}")
                    # 场景跑完后等一下收消息
                    await asyncio.sleep(2)
                else:
                    await interactive_mode(ws)

    except aiohttp.ClientConnectorError:
        print("❌ 连不上后端！先启动: python app.py")
    except KeyboardInterrupt:
        print("\n👋 已退出")


if __name__ == "__main__":
    asyncio.run(main())
