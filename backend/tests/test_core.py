"""
安知手机 后端单元测试。

运行：
  cd backend
  python -m pytest tests/ -v

不需要 API key——memory 测试在 mock 模式运行。
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

import pytest

from anzhi_phone.models import (
    Msg,
    StatusUpdatePayload,
    build_message,
    parse_message,
)
from anzhi_phone.memory import MemoryBridge


# ═══════════════════════════════════════════════════════════
# 协议消息模型
# ═══════════════════════════════════════════════════════════

class TestMsgConstants:
    """消息类型常量测试。"""

    def test_core_message_types_exist(self):
        """核心协议消息类型存在且非空。"""
        assert Msg.MEMORY_SEARCH == "memory_search"
        assert Msg.MEMORY_STORE == "memory_store"
        assert Msg.MOOD_UPDATE == "mood_update"
        assert Msg.CHAT_SYNC == "chat_sync"
        assert Msg.STATUS_UPDATE == "status_update"
        assert Msg.DIARY_STORE == "diary_store"
        assert Msg.HEARTBEAT == "heartbeat"
        assert Msg.MEMORY_RESULT == "memory_result"
        assert Msg.MEMORY_ACK == "memory_ack"
        assert Msg.MOOD_DATA == "mood_data"
        assert Msg.DIARY_ACK == "diary_ack"

    def test_no_llm_proxy_types(self):
        """过渡期 LLM 代理消息类型已移除。"""
        assert not hasattr(Msg, 'UI_TREE')
        assert not hasattr(Msg, 'NOTIFICATION')
        assert not hasattr(Msg, 'USER_MESSAGE')
        assert not hasattr(Msg, 'TAP')
        assert not hasattr(Msg, 'SWIPE')
        assert not hasattr(Msg, 'WAKE')
        assert not hasattr(Msg, 'SPEAK')
        assert not hasattr(Msg, 'TOOL_CALL')


class TestStatusUpdatePayload:
    """设备状态上报测试。"""

    def test_from_dict(self):
        data = {
            "foreground_app": "com.tencent.mm",
            "screen_on": True,
            "battery": 72,
            "charging": False,
            "storage_free_mb": 5000,
            "cpu_temp": 38.5,
            "ram_used_pct": 55,
            "background_processes": ["com.bilibili.app"],
            "light_sensor": 200,
            "silent_mode": False,
        }
        p = StatusUpdatePayload.from_dict(data)
        assert p.battery == 72
        assert p.cpu_temp == 38.5
        assert p.foreground_app == "com.tencent.mm"

    def test_from_empty_dict(self):
        p = StatusUpdatePayload.from_dict({})
        assert p.battery == 0
        assert p.screen_on is True
        assert p.background_processes == []


class TestBuildMessage:
    """消息构建测试。"""

    def test_build_basic(self):
        msg = build_message("memory_result", {"hits": []})
        assert msg["type"] == "memory_result"
        assert msg["payload"] == {"hits": []}
        assert msg["version"] == 1
        assert "id" in msg

    def test_build_empty_payload(self):
        msg = build_message(Msg.HEARTBEAT)
        assert msg["type"] == "heartbeat"
        assert msg["payload"] == {}

    def test_build_heartbeat_omits_id(self):
        msg = build_message("heartbeat", {"ts": 123456})
        assert "id" not in msg
        assert msg["version"] == 1

    def test_build_includes_id_and_version(self):
        msg = build_message("memory_result", {"hits": []})
        assert msg["version"] == 1
        assert len(msg["id"]) > 0

    def test_build_with_explicit_id(self):
        msg = build_message("memory_ack", {"status": "stored"}, msg_id="custom-123")
        assert msg["id"] == "custom-123"


class TestParseMessage:
    """消息解析测试。"""

    def test_parse_basic(self):
        data = {"type": "memory_search", "payload": {"query": "test"}}
        msg_type, payload, msg_id, version = parse_message(data)
        assert msg_type == "memory_search"
        assert payload == {"query": "test"}
        assert msg_id == ""
        assert version == 1

    def test_parse_with_id_and_version(self):
        data = {
            "id": "a1b2c3",
            "type": "memory_search",
            "version": 1,
            "payload": {"query": "免打扰", "top_k": 5},
        }
        msg_type, payload, msg_id, version = parse_message(data)
        assert msg_type == "memory_search"
        assert msg_id == "a1b2c3"
        assert version == 1


# ═══════════════════════════════════════════════════════════
# 记忆库桥接
# ═══════════════════════════════════════════════════════════

class TestMemoryBridge:
    """记忆桥接测试（本地模式，不依赖远程）。"""

    @pytest.mark.asyncio
    async def test_remember_and_recall_local(self):
        """本地缓存写入和读取。"""
        m = MemoryBridge(api_url="http://localhost:9999")  # 故意不可达
        ok = await m.remember("test_key", "test_value")
        assert ok is True
        value = await m.recall("test_key")
        assert value == "test_value"

    @pytest.mark.asyncio
    async def test_recall_miss(self):
        """不存在的 key 返回 None。"""
        m = MemoryBridge(api_url="http://localhost:9999")
        value = await m.recall("nonexistent_key")
        assert value is None

    @pytest.mark.asyncio
    async def test_search_remote_down(self):
        """远程不可用时 search 返回空列表。"""
        m = MemoryBridge(api_url="http://localhost:9999")
        hits = await m.search("test query", top_k=3)
        assert hits == []

    @pytest.mark.asyncio
    async def test_store_local_only(self):
        """远程不可用时 store 仍成功（本地缓存兜底）。"""
        m = MemoryBridge(api_url="http://localhost:9999")
        ok = await m.store("测试文本", source="test", tags=["测试"])
        assert ok is True

    def test_stats(self):
        """缓存统计。"""
        m = MemoryBridge(api_url="http://localhost:9999")
        stats = m.stats
        assert "local_entries" in stats
        assert "remote_available" in stats
        assert "api_url" in stats
