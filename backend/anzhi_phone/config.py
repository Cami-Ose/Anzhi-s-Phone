"""
安知手机 后端配置。

所有配置从环境变量读取，开发环境可用 .env 文件。

手机直调 DeepSeek/Gemini API，VPS 不需要 API key。
VPS 只做协议中转：记忆检索/存储、心情转发、聊天同步、日记存储。
"""

import os
from pathlib import Path

# ── 加载 .env 文件 ──
# 先找 backend/ 目录下的 .env，再找项目根目录的
def _load_dotenv():
    try:
        from dotenv import load_dotenv
        # backend/.env
        backend_env = Path(__file__).parent.parent / ".env"
        if backend_env.exists():
            load_dotenv(backend_env)
            return
        # 项目根 .env
        root_env = Path(__file__).parent.parent.parent / ".env"
        if root_env.exists():
            load_dotenv(root_env)
    except ImportError:
        pass  # python-dotenv 未安装时静默跳过

_load_dotenv()


# ═══════════════════════════════════════════════════════════
# 服务配置
# ═══════════════════════════════════════════════════════════

PORT = int(os.getenv("PORT", "8080"))
HOST = os.getenv("HOST", "0.0.0.0")
WS_MAX_MSG_SIZE = int(os.getenv("WS_MAX_MSG_SIZE", str(10 * 1024 * 1024)))  # 10MB

# ═══════════════════════════════════════════════════════════
# VPS 基础设施 — VPS 只连接各站点，不代调 LLM API
# ═══════════════════════════════════════════════════════════

MEMORY_API_URL = os.getenv("MEMORY_API_URL", "https://memory.example.com")
BODY_API_URL = os.getenv("BODY_API_URL", "https://body.example.com")
CHAT_API_URL = os.getenv("CHAT_API_URL", "https://chat.example.com")

# ═══════════════════════════════════════════════════════════
# 日志
# ═══════════════════════════════════════════════════════════

LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")
