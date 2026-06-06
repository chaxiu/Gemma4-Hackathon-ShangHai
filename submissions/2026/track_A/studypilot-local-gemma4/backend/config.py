from __future__ import annotations

import os
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path
from typing import Optional

from dotenv import load_dotenv


def project_root() -> Path:
    return Path(__file__).resolve().parents[1]


ROOT_DIR = project_root()


def _as_bool(value: Optional[str], default: bool = False) -> bool:
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _as_int(value: Optional[str], default: int) -> int:
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def _as_float(value: Optional[str], default: float) -> float:
    if value is None:
        return default
    try:
        return float(value)
    except ValueError:
        return default


def _resolve_path(raw: str | Path) -> Path:
    path = Path(raw)
    if path.is_absolute():
        return path
    return ROOT_DIR / path


@dataclass(frozen=True)
class AppConfig:
    env: str
    root_dir: Path
    data_dir: Path

    lm_studio_base_url: str
    model_name: str
    llm_temperature: float
    llm_max_tokens: int
    llm_timeout_seconds: int
    use_mock_llm: bool
    llm_fallback_to_mock: bool

    streamlit_port: int
    api_port: int

    normal_max_minutes: int
    tired_max_minutes: int
    exhausted_max_minutes: int
    max_core_tasks_weekday: int
    no_high_intensity_after: str

    parent_confirm_required: bool
    log_level: str

    @property
    def parent_background_path(self) -> Path:
        return self.data_dir / "demo_parent_background.txt"

    @property
    def student_profile_json_path(self) -> Path:
        return self.data_dir / "demo_student_profile.json"

    @property
    def student_profile_md_path(self) -> Path:
        return self.data_dir / "demo_student_profile.md"

    @property
    def rag_chunks_path(self) -> Path:
        return self.data_dir / "rag_chunks.json"

    @property
    def demo_after_school_inputs_path(self) -> Path:
        return self.data_dir / "demo_after_school_inputs.json"

    @property
    def demo_reflection_inputs_path(self) -> Path:
        return self.data_dir / "demo_reflection_inputs.json"

    @property
    def eval_cases_path(self) -> Path:
        return self.data_dir / "eval_cases.jsonl"

    @property
    def runtime_dir(self) -> Path:
        return self.data_dir / "runtime"

    @property
    def daily_logs_dir(self) -> Path:
        return self.runtime_dir / "daily_logs"

    @property
    def plans_dir(self) -> Path:
        return self.runtime_dir / "plans"

    @property
    def traces_dir(self) -> Path:
        return self.runtime_dir / "agent_traces"

    @property
    def profile_snapshots_dir(self) -> Path:
        return self.runtime_dir / "profile_snapshots"

    @property
    def lm_chat_completions_url(self) -> str:
        return f"{self.lm_studio_base_url.rstrip('/')}/chat/completions"

    @property
    def lm_models_url(self) -> str:
        return f"{self.lm_studio_base_url.rstrip('/')}/models"


@lru_cache(maxsize=1)
def get_config() -> AppConfig:
    env_path = ROOT_DIR / ".env"
    if env_path.exists():
        load_dotenv(env_path)
    else:
        load_dotenv()

    data_dir = _resolve_path(os.getenv("STUDYPILOT_DATA_DIR", "data"))

    return AppConfig(
        env=os.getenv("STUDYPILOT_ENV", "local"),
        root_dir=ROOT_DIR,
        data_dir=data_dir,
        lm_studio_base_url=os.getenv("STUDYPILOT_LM_STUDIO_BASE_URL", "http://localhost:1234/v1"),
        model_name=os.getenv("STUDYPILOT_MODEL_NAME", "gemma-4-26b-a4b-instruct"),
        llm_temperature=_as_float(os.getenv("STUDYPILOT_LLM_TEMPERATURE"), 0.2),
        llm_max_tokens=_as_int(os.getenv("STUDYPILOT_LLM_MAX_TOKENS"), 1800),
        llm_timeout_seconds=_as_int(os.getenv("STUDYPILOT_LLM_TIMEOUT_SECONDS"), 90),
        use_mock_llm=_as_bool(os.getenv("STUDYPILOT_USE_MOCK_LLM"), False),
        llm_fallback_to_mock=_as_bool(os.getenv("STUDYPILOT_LLM_FALLBACK_TO_MOCK"), False),
        streamlit_port=_as_int(os.getenv("STUDYPILOT_STREAMLIT_PORT"), 8501),
        api_port=_as_int(os.getenv("STUDYPILOT_API_PORT"), 8000),
        normal_max_minutes=_as_int(os.getenv("STUDYPILOT_NORMAL_MAX_MINUTES"), 60),
        tired_max_minutes=_as_int(os.getenv("STUDYPILOT_TIRED_MAX_MINUTES"), 40),
        exhausted_max_minutes=_as_int(os.getenv("STUDYPILOT_EXHAUSTED_MAX_MINUTES"), 25),
        max_core_tasks_weekday=_as_int(os.getenv("STUDYPILOT_MAX_CORE_TASKS_WEEKDAY"), 3),
        no_high_intensity_after=os.getenv("STUDYPILOT_NO_HIGH_INTENSITY_AFTER", "21:30"),
        parent_confirm_required=_as_bool(os.getenv("STUDYPILOT_PARENT_CONFIRM_REQUIRED"), True),
        log_level=os.getenv("STUDYPILOT_LOG_LEVEL", "INFO"),
    )


def reload_config() -> AppConfig:
    get_config.cache_clear()
    return get_config()
