"""
记忆桥接 —— 连安知现有的记忆库。

两层：
  1. 本地缓存（¥0 ms）  — UI 操作路径等热数据
  2. 远程 API（VPS）     — 向量检索 + 持久存储

API:
  POST /api/retrieve  — 向量检索记忆
  POST /api/ingest    — 摄入新记忆
"""

import asyncio
import json
import logging
import time
from typing import Optional

import aiohttp

from .config import MEMORY_API_URL

logger = logging.getLogger("anzhi-os.memory")

# 远程不可用后多久重试（秒）
RETRY_COOLDOWN = 5 * 60  # 5 分钟


class MemoryBridge:
    """
    连接通过 `MEMORY_API_URL` 配置的记忆服务。

    手机端需要记忆的场景：
      - UI 操作路径缓存（"ui:微信:设免打扰" → 操作序列）
      - Cami 说"记住xxx"
      - 审计日志同步（可选）
    """

    def __init__(self, api_url: str = ""):
        self.api_url = api_url or MEMORY_API_URL
        # 本地热缓存（最快路径）
        self._local: dict[str, dict] = {}
        # 远程状态 + 重试
        self._remote_available: bool | None = None
        self._remote_down_since: float = 0.0

        logger.info(f"记忆桥接已初始化: {self.api_url}")

    # ═══════════════════════════════════════════════════════
    # 公共 API
    # ═══════════════════════════════════════════════════════

    async def remember(self, key: str, value: str) -> bool:
        """
        存一条记忆。先写本地缓存，再异步写远程。

        Args:
            key: 记忆键（如 "ui:com.tencent.mm:设免打扰"）
            value: 记忆值（JSON 字符串或纯文本）

        Returns:
            是否成功
        """
        # 本地缓存总是成功
        self._local[key] = {"key": key, "value": value}

        # 尝试写远程
        ok = await self._remote_post("/api/ingest", {
            "key": key,
            "value": value,
            "source": "anzhi-os",
        })
        if ok:
            logger.debug(f"记忆已摄入: {key}")
        return True  # 本地成功了就算成功

    async def recall(self, key: str) -> Optional[str]:
        """
        精确取一条记忆。先查本地，再查远程。

        Args:
            key: 记忆键

        Returns:
            记忆值，没找到返回 None
        """
        # 第一层：本地缓存
        if key in self._local:
            return str(self._local[key].get("value", ""))

        # 第二层：远程检索
        result = await self._remote_post("/api/retrieve", {
            "query": key,
            "top_k": 1,
            "source": "anzhi-os",
        })
        if result and "results" in result:
            items = result.get("results", [])
            if items:
                value = items[0].get("value", "")
                # 写入本地缓存
                self._local[key] = {"key": key, "value": value}
                return str(value)

        return None

    async def search(self, query: str, top_k: int = 5) -> list[dict]:
        """
        向量语义检索。只在远程做。

        Args:
            query: 自然语言查询
            top_k: 返回条数

        Returns:
            [{"key": "...", "value": "...", "score": 0.95}, ...]
        """
        result = await self._remote_post("/api/retrieve", {
            "query": query,
            "top_k": top_k,
            "source": "anzhi-os",
        })
        if result and "results" in result:
            return result["results"]
        return []

    async def store(self, text: str, source: str = "unknown",
                    tags: Optional[list[str]] = None) -> bool:
        """
        存入一条自由文本记忆（handler 协议消息用）。

        Args:
            text: 记忆文本
            source: 来源标识（chat / os / diary_keep / ...）
            tags: 标签列表

        Returns:
            是否成功
        """
        tags = tags or []
        # 生成唯一 key
        import uuid
        key = f"mem:{source}:{uuid.uuid4().hex[:12]}"
        value = json.dumps({
            "text": text,
            "source": source,
            "tags": tags,
        }, ensure_ascii=False)
        return await self.remember(key, value)

    # ═══════════════════════════════════════════════════════
    # UI 操作路径缓存
    # ═══════════════════════════════════════════════════════

    async def lookup_ui_map(self, app: str, task: str) -> Optional[list]:
        """
        查询 UI 操作路径缓存。

        key 格式: "ui_map:app包名:操作描述"
        命中→直接返回操作序列，不命中→返回 None。
        """
        key = f"ui_map:{app}:{task}"

        # 先查本地
        if key in self._local:
            logger.info(f"🗺 路径缓存命中(本地): {key}")
            return self._local[key].get("actions", [])

        # 再查远程
        result = await self.recall(key)
        if result:
            try:
                actions = json.loads(result)
                if isinstance(actions, list):
                    # 回写本地
                    self._local[key] = {"key": key, "value": result, "actions": actions}
                    logger.info(f"🗺 路径缓存命中(远程): {key}")
                    return actions
            except (json.JSONDecodeError, TypeError):
                pass

        return None

    async def learn_ui_map(self, app: str, task: str, actions: list) -> None:
        """
        安知学会了一个 App 的操作路径。
        下次 Cami 说同样的任务时直接复用，不用再让 DeepSeek 推理。
        """
        key = f"ui_map:{app}:{task}"
        value = json.dumps(actions, ensure_ascii=False)

        self._local[key] = {
            "key": key,
            "value": value,
            "actions": actions,
            "app": app,
            "task": task,
        }
        await self.remember(key, value)
        logger.info(f"安知学会了: {task} @ {app} ({len(actions)} 步)")

    # ═══════════════════════════════════════════════════════
    # 远程 HTTP 调用
    # ═══════════════════════════════════════════════════════

    async def _remote_post(self, path: str, data: dict) -> Optional[dict]:
        """
        调记忆库 HTTP API。

        Args:
            path: API 路径（如 /api/retrieve）
            data: 请求体

        Returns:
            响应 JSON，失败返回 None
        """
        url = f"{self.api_url}{path}"

        # 如果之前试过不可用，等冷却期过了再试一次
        if self._remote_available is False:
            elapsed = time.time() - self._remote_down_since
            if elapsed < RETRY_COOLDOWN:
                return None
            logger.info(f"记忆库冷却期已过，重试连接…")

        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    url,
                    json=data,
                    timeout=aiohttp.ClientTimeout(total=5),
                    headers={"Content-Type": "application/json"},
                ) as resp:
                    if resp.status == 200:
                        self._remote_available = True
                        return await resp.json()
                    elif resp.status in (404, 502, 503):
                        # 服务不存在或挂了，记录下线时间等冷却后重试
                        if self._remote_available is not False:
                            self._remote_down_since = time.time()
                        self._remote_available = False
                        logger.warning(f"记忆库不可用: {resp.status}，{RETRY_COOLDOWN}s 后重试")
                        return None
                    else:
                        logger.warning(f"记忆库返回 {resp.status}: {await resp.text()[:100]}")
                        return None

        except (aiohttp.ClientConnectorError, aiohttp.ServerTimeoutError, asyncio.TimeoutError):
            if self._remote_available is not False:
                self._remote_down_since = time.time()
            self._remote_available = False
            logger.debug(f"记忆库无法连接: {url}")
            return None
        except Exception as e:
            logger.error(f"记忆库调用异常: {e}")
            return None

    @property
    def stats(self) -> dict:
        """缓存统计。"""
        return {
            "local_entries": len(self._local),
            "remote_available": self._remote_available,
            "api_url": self.api_url,
        }


# 单例
_bridge: Optional[MemoryBridge] = None


def get_memory() -> MemoryBridge:
    global _bridge
    if _bridge is None:
        _bridge = MemoryBridge()
    return _bridge
