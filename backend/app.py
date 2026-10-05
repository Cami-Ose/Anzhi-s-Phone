"""
安知手机 后端入口 —— WebSocket 协议中转服务。

VPS 不做 LLM 推理，仅做协议中转：
  - 记忆检索/存储 → memory.example.com
  - 心情更新 → body.example.com
  - 聊天同步 → chat.example.com
  - 日记存储 → 记忆库

启动：
  pip install -r requirements.txt
  python app.py

然后手机端连 ws://<vps-ip>:8080/anzhi?device=<device-id>
"""

import asyncio
import json
import logging

from aiohttp import web

from anzhi_phone import config  # 触发 .env 加载
from anzhi_phone.handler import AnzhiPhoneHandler

# ── 日志 ──
logging.basicConfig(
    level=getattr(logging, config.LOG_LEVEL),
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s"
)
logger = logging.getLogger("anzhi-os")

# ── 全局状态：已连接的设备 ──
# device_id → AnzhiPhoneHandler
connected_devices: dict[str, AnzhiPhoneHandler] = {}


async def websocket_handler(request: web.Request) -> web.WebSocketResponse:
    """
    手机端连上来时触发。
    一个 WebSocket 连接 = 一台手机。
    """

    device_id = request.query.get("device", "unknown")
    ws = web.WebSocketResponse(max_msg_size=config.WS_MAX_MSG_SIZE)
    await ws.prepare(request)

    logger.info(f"📱 手机已连接: {device_id}")

    # 创建这个设备的 handler
    def send_func(msg_type: str, payload: dict = None) -> None:
        """给这台手机发消息"""
        if ws.closed:
            return
        data = json.dumps({
            "type": msg_type,
            "payload": payload or {}
        })
        asyncio.create_task(ws.send_str(data))

    handler = AnzhiPhoneHandler(device_id, send_func)
    connected_devices[device_id] = handler

    try:
        async for msg in ws:
            if msg.type == aiohttp.WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                    msg_type = data.get("type", "")
                    payload = data.get("payload", {})
                    msg_id = data.get("id", "")
                    version = data.get("version", 1)

                    # 把 msg_id 注入 payload，handler 回复时可以 echo 回来配对
                    if isinstance(payload, dict) and msg_id:
                        payload["_msg_id"] = msg_id

                    await handler.handle(msg_type, payload)

                except json.JSONDecodeError:
                    logger.warning(f"[{device_id}] 非 JSON 消息: {msg.data[:100]}")
                except Exception as e:
                    logger.error(f"[{device_id}] 处理消息异常: {e}")

            elif msg.type == aiohttp.WSMsgType.ERROR:
                logger.error(f"[{device_id}] WebSocket 错误: {ws.exception()}")

    except asyncio.CancelledError:
        logger.info(f"[{device_id}] 连接被取消")
    finally:
        logger.info(f"📱 手机已断开: {device_id}")
        connected_devices.pop(device_id, None)

    return ws


async def health_check(request: web.Request) -> web.Response:
    """健康检查接口"""
    return web.json_response({
        "status": "ok",
        "connected_devices": len(connected_devices),
        "devices": list(connected_devices.keys()),
    })


def main():
    app = web.Application()
    app.router.add_get("/anzhi", websocket_handler)
    app.router.add_get("/health", health_check)

    logger.info(f"🚀 安知手机 后端启动: ws://0.0.0.0:{config.PORT}/anzhi")
    web.run_app(app, host=config.HOST, port=config.PORT)


if __name__ == "__main__":
    main()
