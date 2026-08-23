"""
安知手机 消息协议模型（BUILD.md §2.1）。

所有消息走 WebSocket，格式：
  { "id": "<uuid>", "type": "<消息类型>", "version": 1, "payload": { ... } }

铁律：所有消息强制包含 id（UUID）、type（String）、version（Int，当前=1）。
心跳 id 可省略，version 不可省。
id 用于异步请求-响应配对，version 用于协议演进。

VPS 仅中转 4 类核心协议消息：
  OS → VPS：memory_search / memory_store / mood_update / chat_sync / diary_store / heartbeat
  VPS → OS：memory_result / memory_ack / mood_data / diary_ack
"""

import uuid
from dataclasses import dataclass, field, asdict
from typing import Any, Optional


# ═══════════════════════════════════════════════════════════════
# 消息类型常量
# ═══════════════════════════════════════════════════════════════

class Msg:
    """WebSocket 消息类型。OS ↔ 后端 双向。"""

    # ── OS → VPS（协议消息）──
    MEMORY_SEARCH  = "memory_search"   # 向量检索记忆词条
    MEMORY_STORE   = "memory_store"    # 存入新记忆
    MOOD_UPDATE    = "mood_update"     # 上报心情相关事件
    CHAT_SYNC      = "chat_sync"       # 聊天记录同步（整轮上传）
    STATUS_UPDATE  = "status_update"   # 设备状态上报（VPS 仅记录日志，不处理）
    DIARY_STORE    = "diary_store"     # OS 从 Keep 读取新日记，提交 VPS 存储
    HEARTBEAT      = "heartbeat"       # 心跳保活

    # ── VPS → OS（协议消息）──
    MEMORY_RESULT  = "memory_result"   # 记忆检索结果
    MEMORY_ACK     = "memory_ack"      # 记忆存储确认
    MOOD_DATA      = "mood_data"       # 心情数据
    DIARY_ACK      = "diary_ack"       # 日记存储确认


# ═══════════════════════════════════════════════════════════════
# 协议消息 Payload 数据类
# ═══════════════════════════════════════════════════════════════

@dataclass
class StatusUpdatePayload:
    """设备状态上报"""
    foreground_app: str = ""
    screen_on: bool = True
    battery: int = 0
    charging: bool = False
    storage_free_mb: int = 0
    cpu_temp: float = 0.0
    ram_used_pct: int = 0
    background_processes: list[str] = field(default_factory=list)
    light_sensor: int = 0
    silent_mode: bool = False

    @classmethod
    def from_dict(cls, d: dict) -> "StatusUpdatePayload":
        return cls(
            foreground_app=d.get("foreground_app", ""),
            screen_on=d.get("screen_on", True),
            battery=d.get("battery", 0),
            charging=d.get("charging", False),
            storage_free_mb=d.get("storage_free_mb", 0),
            cpu_temp=d.get("cpu_temp", 0.0),
            ram_used_pct=d.get("ram_used_pct", 0),
            background_processes=d.get("background_processes", []),
            light_sensor=d.get("light_sensor", 0),
            silent_mode=d.get("silent_mode", False),
        )


# ═══════════════════════════════════════════════════════════════
# 工具函数
# ═══════════════════════════════════════════════════════════════

def build_message(msg_type: str, payload: Any = None,
                  msg_id: str = "", version: int = 1) -> dict:
    """
    构建发送给手机的消息 dict。

    Args:
        msg_type: 消息类型（见 Msg 常量）
        payload: dataclass 实例或普通 dict
        msg_id: 消息 ID（UUID）。空字符串时自动生成。
        version: 协议版本号，当前=1

    Returns:
        {"id": "...", "type": "...", "version": 1, "payload": {...}}
    """
    if payload is None:
        payload = {}
    elif hasattr(payload, "__dataclass_fields__"):
        payload = asdict(payload)

    msg = {
        "type": msg_type,
        "version": version,
        "payload": payload,
    }
    # heartbeat 可省略 id，其他消息自动生成
    if msg_type != Msg.HEARTBEAT and not msg_id:
        msg_id = str(uuid.uuid4())
    if msg_id:
        msg["id"] = msg_id
    return msg


def parse_message(data: dict) -> tuple[str, dict, str, int]:
    """
    从原始 WebSocket JSON 解析出 (msg_type, payload, msg_id, version)。

    Returns:
        (msg_type, payload, msg_id, version)
    """
    return (
        data.get("type", ""),
        data.get("payload", {}),
        data.get("id", ""),
        data.get("version", 1),
    )
