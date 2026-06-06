from __future__ import annotations

import json
import shutil
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

from backend.config import AppConfig, get_config
from backend.json_utils import json_dumps_cn


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def today_str() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def timestamp_slug() -> str:
    return datetime.now().strftime("%Y%m%d_%H%M%S")


def ensure_data_dirs(config: AppConfig | None = None) -> None:
    cfg = config or get_config()
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    cfg.runtime_dir.mkdir(parents=True, exist_ok=True)
    cfg.daily_logs_dir.mkdir(parents=True, exist_ok=True)
    cfg.plans_dir.mkdir(parents=True, exist_ok=True)
    cfg.traces_dir.mkdir(parents=True, exist_ok=True)
    cfg.profile_snapshots_dir.mkdir(parents=True, exist_ok=True)


def read_text(path: str | Path, default: str = "") -> str:
    file_path = Path(path)
    if not file_path.exists():
        return default
    return file_path.read_text(encoding="utf-8")


def write_text(path: str | Path, text: str) -> None:
    file_path = Path(path)
    file_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = file_path.with_suffix(file_path.suffix + ".tmp")
    tmp_path.write_text(text, encoding="utf-8")
    tmp_path.replace(file_path)


def load_json(path: str | Path, default: Any = None) -> Any:
    file_path = Path(path)
    if not file_path.exists():
        return default
    with file_path.open("r", encoding="utf-8") as f:
        return json.load(f)


def save_json(path: str | Path, data: Any, *, indent: int = 2) -> None:
    write_text(path, json_dumps_cn(data, indent=indent) + "\n")


def append_jsonl(path: str | Path, item: dict[str, Any]) -> None:
    file_path = Path(path)
    file_path.parent.mkdir(parents=True, exist_ok=True)
    with file_path.open("a", encoding="utf-8") as f:
        f.write(json.dumps(item, ensure_ascii=False) + "\n")


def read_jsonl(path: str | Path) -> list[dict[str, Any]]:
    file_path = Path(path)
    if not file_path.exists():
        return []

    rows: list[dict[str, Any]] = []
    with file_path.open("r", encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                rows.append(json.loads(stripped))
            except json.JSONDecodeError as exc:
                rows.append(
                    {
                        "case_id": f"invalid_line_{line_no}",
                        "type": "invalid_jsonl",
                        "error": str(exc),
                        "raw": stripped,
                    }
                )
    return rows


def list_files(path: str | Path, pattern: str = "*") -> list[Path]:
    directory = Path(path)
    if not directory.exists():
        return []
    return sorted(directory.glob(pattern), key=lambda p: p.stat().st_mtime, reverse=True)


def latest_file(path: str | Path, pattern: str = "*.json") -> Path | None:
    files = list_files(path, pattern)
    return files[0] if files else None


def reset_directory(path: str | Path) -> None:
    directory = Path(path)
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True, exist_ok=True)


def load_student_profile(config: AppConfig | None = None) -> dict[str, Any]:
    cfg = config or get_config()
    return load_json(cfg.student_profile_json_path, default={}) or {}


def save_student_profile(profile: dict[str, Any], config: AppConfig | None = None) -> None:
    cfg = config or get_config()
    ensure_data_dirs(cfg)

    snapshot_path = cfg.profile_snapshots_dir / f"student_profile_{timestamp_slug()}.json"
    old_profile = load_json(cfg.student_profile_json_path, default=None)
    if old_profile:
        save_json(snapshot_path, old_profile)

    profile["updated_at"] = now_iso()
    save_json(cfg.student_profile_json_path, profile)


def load_student_profile_markdown(config: AppConfig | None = None) -> str:
    cfg = config or get_config()
    return read_text(cfg.student_profile_md_path, default="")


def save_student_profile_markdown(markdown: str, config: AppConfig | None = None) -> None:
    cfg = config or get_config()
    ensure_data_dirs(cfg)
    write_text(cfg.student_profile_md_path, markdown.strip() + "\n")


def load_rag_chunks(config: AppConfig | None = None) -> list[dict[str, Any]]:
    cfg = config or get_config()
    data = load_json(cfg.rag_chunks_path, default=[])
    return data if isinstance(data, list) else []


def save_rag_chunks(chunks: list[dict[str, Any]], config: AppConfig | None = None) -> None:
    cfg = config or get_config()
    ensure_data_dirs(cfg)
    save_json(cfg.rag_chunks_path, chunks)


def save_plan(plan: dict[str, Any], config: AppConfig | None = None) -> Path:
    cfg = config or get_config()
    ensure_data_dirs(cfg)
    date = plan.get("date") or today_str()
    plan_id = plan.get("plan_id") or f"plan_{timestamp_slug()}"
    path = cfg.plans_dir / f"{date}_{plan_id}.json"
    save_json(path, plan)
    return path


def latest_plan(config: AppConfig | None = None) -> dict[str, Any] | None:
    cfg = config or get_config()
    path = latest_file(cfg.plans_dir, "*.json")
    if path is None:
        return None
    return load_json(path, default=None)


def save_daily_log(log: dict[str, Any], config: AppConfig | None = None) -> Path:
    cfg = config or get_config()
    ensure_data_dirs(cfg)
    date = log.get("date") or today_str()
    path = cfg.daily_logs_dir / f"{date}.json"
    save_json(path, log)
    return path


def latest_daily_log(config: AppConfig | None = None) -> dict[str, Any] | None:
    cfg = config or get_config()
    path = latest_file(cfg.daily_logs_dir, "*.json")
    if path is None:
        return None
    return load_json(path, default=None)


def save_agent_trace(trace: dict[str, Any], config: AppConfig | None = None) -> Path:
    cfg = config or get_config()
    ensure_data_dirs(cfg)
    trace_id = trace.get("trace_id") or f"trace_{timestamp_slug()}"
    path = cfg.traces_dir / f"{trace_id}.json"
    save_json(path, trace)
    return path


def clear_runtime_data(config: AppConfig | None = None) -> None:
    cfg = config or get_config()
    reset_directory(cfg.runtime_dir)
    ensure_data_dirs(cfg)


def copy_many(files: Iterable[tuple[Path, Path]]) -> None:
    for source, target in files:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
