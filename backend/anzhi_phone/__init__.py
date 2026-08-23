"""
安知手机 后端 —— 手机 ↔ 安知大脑的消息中转站。

架构变更（2026-07）：
  VPS 退化回纯协议中转，手机直调 LLM API。
  废弃 Python 模块已删除，功能已迁移到手机端 Kotlin。

模块：
  handler.py  — 协议消息路由（memory/mood/chat_sync/diary 仅 4 类）
  models.py   — WebSocket 消息协议定义
  memory.py   — 记忆库桥接（地址由 MEMORY_API_URL 配置）
  config.py   — 环境变量配置

已删除（迁移到手机端 Kotlin）：
  bot.py                  → 手机本地 AnzhiWakeManager
  llm.py                  → 手机直调
  notification_filter.py  → 手机本地三级过滤
  phone_manager.py        → 手机本地 ThermalGuard
  dashboard.py            → 手机本地
  ui_parser.py            → 手机本地
"""

__version__ = "0.3.0"
