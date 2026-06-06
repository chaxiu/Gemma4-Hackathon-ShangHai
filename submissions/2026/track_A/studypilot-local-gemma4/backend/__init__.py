"""
StudyPilot Local V2 backend package.

轻舟学伴的后端模块保持小而清晰：
- LLM 调用只放在 llm_client.py
- Prompt 只放在 prompts.py
- 文件读写只放在 storage.py
- RAG 只放在 rag_store.py
- 减负规则只放在 rule_engine.py
- 结构校验只放在 validator.py
"""

__version__ = "2.0.0"
__app_name__ = "StudyPilot Local V2"
