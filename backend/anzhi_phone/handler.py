"""
安知手机 协议中转 —— 手机 ↔ VPS 的消息路由。

架构变更（2026-07）：
  VPS 不再执行任何 LLM 调用、设备管理、通知过滤、UI 分析。
  手机直调 DeepSeek/Gemini + 本地 CDP 官网安知。
  VPS 只做 4 类协议消息的纯中转。

保留的协议消息：
  memory_search / memory_store  → 记忆库（memory.example.com）
  mood_update                    → 心情服务（body.example.com，TODO）
  chat_sync                      → 聊天记录同步（chat.example.com，TODO）
  diary_store                    → 日记存储（暂存记忆库）
  status_update                  → 仅记录日志，不处理
  heartbeat                      → 静默丢弃
"""

import json
import logging
from typing import Callable

from .models import Msg
from .memory import get_memory

logger = logging.getLogger("anzhi-os.handler")


class AnzhiPhoneHandler:
    """
    每个连接的手机对应一个 handler 实例。
    仅做协议消息中转，不做任何业务决策。
    """

    def __init__(self, device_id: str, send_func: Callable):
        """
        Args:
            device_id: 设备唯一标识（如 "<device-id>"）
            send_func: 给手机发消息的函数。签名: (msg_type: str, payload: dict) -> None
        """
        self.device_id = device_id
        self.send = send_func
        self.memory = get_memory()
        self.current_app: str = ""

        logger.info(f"安知手机 Handler 已创建: {device_id}")

    # ═══════════════════════════════════════════════════════════
    # 入口：手机发来消息 → 分流
    # ═══════════════════════════════════════════════════════════

    async def handle(self, msg_type: str, payload: dict) -> None:
        """手机发来消息，按类型分流到对应处理。"""
        logger.debug(f"[{self.device_id}] ← {msg_type}")

        if msg_type == Msg.MEMORY_SEARCH:
            await self._handle_memory_search(payload)

        elif msg_type == Msg.MEMORY_STORE:
            await self._handle_memory_store(payload)

        elif msg_type == Msg.MOOD_UPDATE:
            await self._handle_mood_update(payload)

        elif msg_type == Msg.CHAT_SYNC:
            await self._handle_chat_sync(payload)

        elif msg_type == Msg.STATUS_UPDATE:
            await self._handle_status_update(payload)

        elif msg_type == Msg.DIARY_STORE:
            await self._handle_diary_store(payload)

        elif msg_type == Msg.HEARTBEAT:
            pass  # 心跳无需处理

        else:
            logger.debug(f"[{self.device_id}] 不处理的消息类型: {msg_type}")

    # ═══════════════════════════════════════════════════════════
    # 协议消息处理
    # ═══════════════════════════════════════════════════════════

    async def _handle_memory_search(self, payload: dict) -> None:
        """
        OS 请求记忆库向量检索 → 转发到 memory.example.com

        请求: { "query": "...", "top_k": 5 }
        响应: MEMORY_RESULT { "query": "...", "hits": [...] }
        """
        query = payload.get("query", "")
        top_k = payload.get("top_k", 5)

        logger.info(f"[{self.device_id}] 🔍 记忆检索: {query[:50]} (top_k={top_k})")

        try:
            hits = await self.memory.search(query, top_k)
        except Exception as e:
            logger.error(f"[{self.device_id}] 记忆检索失败: {e}")
            hits = []

        self.send(Msg.MEMORY_RESULT, {
            "query": query,
            "hits": hits,
        })

    async def _handle_memory_store(self, payload: dict) -> None:
        """
        OS 请求存入新记忆 → 转发到 memory.example.com

        请求: { "text": "...", "source": "...", "tags": [...] }
        响应: MEMORY_ACK { "status": "stored" }
        """
        text = payload.get("text", "")
        source = payload.get("source", "unknown")
        tags = payload.get("tags", [])

        logger.info(f"[{self.device_id}] 💾 记忆存入: {text[:50]} (source={source})")

        try:
            await self.memory.store(text, source, tags)
            status = "stored"
        except Exception as e:
            logger.error(f"[{self.device_id}] 记忆存入失败: {e}")
            status = "error"

        self.send(Msg.MEMORY_ACK, {"status": status})

    async def _handle_mood_update(self, payload: dict) -> None:
        """
        OS 上报心情事件 → 转发到 body.example.com（TODO）

        当前阶段：仅记录日志，后续对接心情计算端点。
        """
        event = payload.get("event", "")
        detail = payload.get("detail", "")
        logger.info(f"[{self.device_id}] 💭 心情事件: {event} — {detail}")
        # TODO: POST body.example.com/api/mood

    async def _handle_chat_sync(self, payload: dict) -> None:
        """
        OS 上传整轮聊天记录 → 转发到 chat.example.com（TODO）

        当前阶段：仅记录日志，后续对接聊天站点 API。
        """
        session_id = payload.get("session_id", "")
        window = payload.get("window", "unknown")
        messages = payload.get("messages", [])
        today_log = payload.get("today_log", [])

        logger.info(
            f"[{self.device_id}] 💬 chat_sync: session={session_id}, "
            f"window={window}, messages={len(messages)}, log_entries={len(today_log)}"
        )
        # TODO: POST chat.example.com/api/chat_sync

    async def _handle_status_update(self, payload: dict) -> None:
        """
        OS 上报设备状态。VPS 仅记录日志，不处理。
        所有设备管理和温度控制由手机本地负责。
        """
        app = payload.get("foreground_app", "")
        self.current_app = app
        logger.debug(
            f"[{self.device_id}] 状态更新: app={app}, "
            f"battery={payload.get('battery')}%, "
            f"temp={payload.get('cpu_temp')}°C"
        )

    async def _handle_diary_store(self, payload: dict) -> None:
        """
        OS 从 Google Keep 读取新日记后提交 VPS 存储。

        请求: { "date": "...", "text": "...", "source": "keep" }
        响应: DIARY_ACK { "date": "...", "status": "stored" }

        当前暂存到记忆库（tag="日记"），后续独立日记存储端点。
        """
        date = payload.get("date", "")
        text = payload.get("text", "")
        source = payload.get("source", "keep")

        logger.info(
            f"[{self.device_id}] 📔 diary_store: date={date}, "
            f"source={source}, len={len(text)}"
        )

        try:
            await self.memory.store(
                text=f"[日记 {date}] {text}",
                source=f"diary_{source}",
                tags=["日记", date],
            )
            status = "stored"
        except Exception as e:
            logger.error(f"[{self.device_id}] 日记存储失败: {e}")
            status = "error"

        self.send(Msg.DIARY_ACK, {"date": date, "status": status})
