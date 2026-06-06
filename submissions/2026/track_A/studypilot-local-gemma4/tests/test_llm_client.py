from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import backend.llm_client as llm_client_module
from backend.config import AppConfig
from backend.llm_client import LLMClient


def _config() -> AppConfig:
    base_cfg = llm_client_module.get_config()
    return AppConfig(
        env="test",
        root_dir=base_cfg.root_dir,
        data_dir=base_cfg.data_dir,
        lm_studio_base_url="http://localhost:1234/v1",
        model_name="gemma-4-26b-a4b-it",
        llm_temperature=0.2,
        llm_max_tokens=100,
        llm_timeout_seconds=30,
        use_mock_llm=False,
        llm_fallback_to_mock=False,
        streamlit_port=8501,
        api_port=8000,
        normal_max_minutes=60,
        tired_max_minutes=40,
        exhausted_max_minutes=25,
        max_core_tasks_weekday=3,
        no_high_intensity_after="21:30",
        parent_confirm_required=True,
        log_level="INFO",
    )


class _FakeModelsResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, Any]:
        return {"data": []}


class _FakeChatResponse:
    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict[str, Any]:
        return {"model": "gemma-4-26b-a4b-it", "choices": [{"message": {"content": "{\"ok\": true}"}}]}


class _RecordingClient:
    calls: list[dict[str, Any]] = []

    def __init__(self, **kwargs: Any) -> None:
        self.calls.append(kwargs)

    def __enter__(self) -> "_RecordingClient":
        return self

    def __exit__(self, *args: object) -> None:
        return None

    def get(self, url: str) -> _FakeModelsResponse:
        return _FakeModelsResponse()

    def post(self, url: str, json: dict[str, Any]) -> _FakeChatResponse:
        return _FakeChatResponse()


def test_health_bypasses_system_proxy_for_local_lm_studio(monkeypatch) -> None:
    _RecordingClient.calls.clear()
    monkeypatch.setattr(llm_client_module.httpx, "Client", _RecordingClient)

    result = LLMClient(_config()).health()

    assert result["ok"] is True
    assert _RecordingClient.calls[0]["trust_env"] is False


def test_chat_bypasses_system_proxy_for_local_lm_studio(monkeypatch) -> None:
    _RecordingClient.calls.clear()
    monkeypatch.setattr(llm_client_module.httpx, "Client", _RecordingClient)

    result = LLMClient(_config()).chat([{"role": "user", "content": "hi"}])

    assert result.text == "{\"ok\": true}"
    assert _RecordingClient.calls[0]["trust_env"] is False
