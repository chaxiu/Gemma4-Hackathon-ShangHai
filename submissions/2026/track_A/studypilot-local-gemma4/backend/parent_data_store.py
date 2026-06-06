from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from backend.config import AppConfig, get_config
from backend.rag_store import build_chunks_from_profile, profile_to_markdown
from backend.run_context import RUN_MODE_DEBUG, RUN_MODE_OFFICIAL
from backend.storage import ensure_data_dirs, load_student_profile, now_iso, save_rag_chunks, save_student_profile, save_student_profile_markdown, today_str


def _json(data: Any) -> str:
    return json.dumps(data, ensure_ascii=False)


def _loads(raw: str | None, default: Any) -> Any:
    if not raw:
        return default
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return default


class ParentDataStore:
    """SQLite persistence for parent-facing daily truth and review data."""

    def __init__(self, config: AppConfig | None = None):
        self.config = config or get_config()
        ensure_data_dirs(self.config)
        self.db_path = self.config.runtime_dir / "studypilot.db"
        self._init_db()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        return conn

    def _migrate_run_context_columns(self, conn: sqlite3.Connection) -> None:
        additions = {
            "business_date": "TEXT",
            "run_mode": "TEXT NOT NULL DEFAULT 'official'",
            "session_id": "TEXT",
            "is_canonical": "INTEGER NOT NULL DEFAULT 1",
            "revision": "INTEGER NOT NULL DEFAULT 1",
            "superseded_at": "TEXT",
        }
        for table in ["daily_plans", "daily_logs", "task_outcomes", "pending_tasks", "parent_corrections"]:
            existing = {row["name"] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
            for column, definition in additions.items():
                if column not in existing:
                    conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
            conn.execute(f"UPDATE {table} SET business_date = date WHERE business_date IS NULL OR business_date = ''")
            conn.execute(f"UPDATE {table} SET run_mode = 'official' WHERE run_mode IS NULL OR run_mode = ''")
            conn.execute(f"UPDATE {table} SET session_id = 'official:' || business_date WHERE session_id IS NULL OR session_id = ''")

    def _normalize_context(self, date: str, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> dict[str, Any]:
        mode = RUN_MODE_DEBUG if run_mode == RUN_MODE_DEBUG else RUN_MODE_OFFICIAL
        business_date = date
        normalized_session_id = session_id or (f"official:{business_date}" if mode == RUN_MODE_OFFICIAL else f"debug:{business_date}:{datetime.now().strftime('%Y%m%d_%H%M%S%f')}")
        canonical_plan_id = f"official:{business_date}" if mode == RUN_MODE_OFFICIAL else normalized_session_id
        return {
            "business_date": business_date,
            "run_mode": mode,
            "session_id": normalized_session_id,
            "canonical_plan_id": canonical_plan_id,
        }

    def _next_revision(self, conn: sqlite3.Connection, table: str, business_date: str, run_mode: str, session_id: str) -> int:
        row = conn.execute(
            f"SELECT MAX(revision) AS max_revision FROM {table} WHERE business_date = ? AND run_mode = ? AND session_id = ?",
            (business_date, run_mode, session_id),
        ).fetchone()
        return int(row["max_revision"] or 0) + 1

    def _init_db(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS daily_plans (
                    date TEXT NOT NULL,
                    plan_id TEXT NOT NULL,
                    business_date TEXT,
                    run_mode TEXT NOT NULL DEFAULT 'official',
                    session_id TEXT,
                    is_canonical INTEGER NOT NULL DEFAULT 1,
                    revision INTEGER NOT NULL DEFAULT 1,
                    superseded_at TEXT,
                    plan_title TEXT,
                    total_minutes INTEGER,
                    source TEXT,
                    tasks_json TEXT NOT NULL,
                    raw_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (date, plan_id)
                );

                CREATE TABLE IF NOT EXISTS daily_logs (
                    date TEXT NOT NULL,
                    plan_id TEXT NOT NULL,
                    business_date TEXT,
                    run_mode TEXT NOT NULL DEFAULT 'official',
                    session_id TEXT,
                    is_canonical INTEGER NOT NULL DEFAULT 1,
                    revision INTEGER NOT NULL DEFAULT 1,
                    superseded_at TEXT,
                    log_id TEXT,
                    source_input TEXT,
                    summary TEXT,
                    completion_rate REAL,
                    energy_level TEXT,
                    mood_signal TEXT,
                    encouragement TEXT,
                    closed_loop_status TEXT,
                    raw_json TEXT NOT NULL,
                    created_at TEXT,
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (date, plan_id)
                );

                CREATE TABLE IF NOT EXISTS task_outcomes (
                    outcome_id TEXT PRIMARY KEY,
                    date TEXT NOT NULL,
                    plan_id TEXT NOT NULL,
                    business_date TEXT,
                    run_mode TEXT NOT NULL DEFAULT 'official',
                    session_id TEXT,
                    is_canonical INTEGER NOT NULL DEFAULT 1,
                    revision INTEGER NOT NULL DEFAULT 1,
                    superseded_at TEXT,
                    title TEXT NOT NULL,
                    subject TEXT,
                    status TEXT NOT NULL CHECK (status IN ('completed', 'partial', 'missed')),
                    evidence TEXT,
                    reason TEXT,
                    remaining TEXT,
                    corrected INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS pending_tasks (
                    pending_id TEXT PRIMARY KEY,
                    date TEXT NOT NULL,
                    plan_id TEXT,
                    business_date TEXT,
                    run_mode TEXT NOT NULL DEFAULT 'official',
                    session_id TEXT,
                    is_canonical INTEGER NOT NULL DEFAULT 1,
                    revision INTEGER NOT NULL DEFAULT 1,
                    superseded_at TEXT,
                    title TEXT NOT NULL,
                    subject TEXT,
                    reason TEXT,
                    suggested_next_step TEXT,
                    status TEXT NOT NULL DEFAULT 'open',
                    source_date TEXT,
                    created_at TEXT NOT NULL,
                    resolved_at TEXT
                );

                CREATE TABLE IF NOT EXISTS parent_corrections (
                    correction_id TEXT PRIMARY KEY,
                    date TEXT NOT NULL,
                    business_date TEXT,
                    run_mode TEXT NOT NULL DEFAULT 'official',
                    session_id TEXT,
                    is_canonical INTEGER NOT NULL DEFAULT 1,
                    revision INTEGER NOT NULL DEFAULT 1,
                    superseded_at TEXT,
                    target_type TEXT NOT NULL,
                    target_id TEXT NOT NULL,
                    field TEXT NOT NULL,
                    old_value TEXT,
                    new_value TEXT,
                    reason TEXT,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS profile_snapshots (
                    snapshot_id TEXT PRIMARY KEY,
                    path TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    reason TEXT
                );
                """
            )
            self._migrate_run_context_columns(conn)

    def upsert_plan(self, plan: dict[str, Any], *, source: str = "agent", run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> None:
        date = str(plan.get("date") or today_str())
        now = now_iso()
        with self._connect() as conn:
            context = self._normalize_context(date, run_mode, session_id)
            revision = self._next_revision(conn, "daily_plans", context["business_date"], context["run_mode"], context["session_id"])
            plan_id = f"{context['canonical_plan_id']}::r{revision}" if context["run_mode"] == RUN_MODE_OFFICIAL else str(plan.get("plan_id") or context["canonical_plan_id"])
            plan = dict(plan)
            plan["plan_id"] = plan_id
            plan["run_mode"] = context["run_mode"]
            plan["session_id"] = context["session_id"]
            plan["business_date"] = context["business_date"]
            plan["revision"] = revision
            plan["is_canonical"] = 1
            conn.execute(
                """
                UPDATE daily_plans
                SET is_canonical = 0, superseded_at = ?
                WHERE business_date = ? AND run_mode = ? AND session_id = ? AND is_canonical = 1
                """,
                (now, context["business_date"], context["run_mode"], context["session_id"]),
            )
            conn.execute(
                """
                INSERT INTO daily_plans(date, plan_id, business_date, run_mode, session_id, is_canonical, revision, superseded_at,
                                        plan_title, total_minutes, source, tasks_json, raw_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, NULL, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(date, plan_id) DO UPDATE SET
                    business_date = excluded.business_date,
                    run_mode = excluded.run_mode,
                    session_id = excluded.session_id,
                    is_canonical = 1,
                    revision = excluded.revision,
                    superseded_at = NULL,
                    plan_title = excluded.plan_title,
                    total_minutes = excluded.total_minutes,
                    source = excluded.source,
                    tasks_json = excluded.tasks_json,
                    raw_json = excluded.raw_json,
                    updated_at = excluded.updated_at
                """,
                (
                    date,
                    plan_id,
                    context["business_date"],
                    context["run_mode"],
                    context["session_id"],
                    revision,
                    plan.get("plan_title"),
                    int(plan.get("total_minutes") or plan.get("available_minutes") or 0),
                    source,
                    _json(plan.get("tasks", []) or []),
                    _json(plan),
                    now,
                    now,
                ),
            )

    def upsert_daily_log(self, daily_log: dict[str, Any], *, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> None:
        date = str(daily_log.get("date") or today_str())
        log_id = str(daily_log.get("log_id") or f"log_{date}")
        feedback = daily_log.get("feedback", {}) or {}
        now = now_iso()
        with self._connect() as conn:
            context = self._normalize_context(date, run_mode, session_id)
            revision = self._next_revision(conn, "daily_logs", context["business_date"], context["run_mode"], context["session_id"])
            plan_id = f"{context['canonical_plan_id']}::r{revision}" if context["run_mode"] == RUN_MODE_OFFICIAL else str(daily_log.get("plan_id") or context["canonical_plan_id"])
            daily_log = dict(daily_log)
            daily_log["plan_id"] = plan_id
            daily_log["run_mode"] = context["run_mode"]
            daily_log["session_id"] = context["session_id"]
            daily_log["business_date"] = context["business_date"]
            daily_log["revision"] = revision
            daily_log["is_canonical"] = 1
            conn.execute(
                """
                UPDATE daily_logs
                SET is_canonical = 0, superseded_at = ?
                WHERE business_date = ? AND run_mode = ? AND session_id = ? AND is_canonical = 1
                """,
                (now, context["business_date"], context["run_mode"], context["session_id"]),
            )
            conn.execute(
                """
                INSERT INTO daily_logs(date, plan_id, business_date, run_mode, session_id, is_canonical, revision, superseded_at,
                                       log_id, source_input, summary, completion_rate, energy_level, mood_signal,
                                       encouragement, closed_loop_status, raw_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(date, plan_id) DO UPDATE SET
                    business_date = excluded.business_date,
                    run_mode = excluded.run_mode,
                    session_id = excluded.session_id,
                    is_canonical = 1,
                    revision = excluded.revision,
                    superseded_at = NULL,
                    log_id = excluded.log_id,
                    source_input = excluded.source_input,
                    summary = excluded.summary,
                    completion_rate = excluded.completion_rate,
                    energy_level = excluded.energy_level,
                    mood_signal = excluded.mood_signal,
                    encouragement = excluded.encouragement,
                    closed_loop_status = excluded.closed_loop_status,
                    raw_json = excluded.raw_json,
                    updated_at = excluded.updated_at
                """,
                (
                    date,
                    plan_id,
                    context["business_date"],
                    context["run_mode"],
                    context["session_id"],
                    revision,
                    log_id,
                    daily_log.get("source_input", ""),
                    daily_log.get("summary", ""),
                    float(daily_log.get("completion_rate") or 0),
                    daily_log.get("energy_level", "unknown"),
                    daily_log.get("mood_signal", ""),
                    feedback.get("encouragement") or feedback.get("child_feedback") or "",
                    daily_log.get("closed_loop_status", feedback.get("closed_loop_status", "partial")),
                    _json(daily_log),
                    daily_log.get("created_at") or now,
                    now,
                ),
            )
            conn.execute("DELETE FROM task_outcomes WHERE date = ? AND plan_id = ? AND run_mode = ? AND session_id = ? AND corrected = 0", (date, plan_id, context["run_mode"], context["session_id"]))
            for item in self._task_items(daily_log):
                outcome_id = self._outcome_id(date, plan_id, item["title"], context["run_mode"], context["session_id"])
                if self._has_corrected_outcome(conn, outcome_id):
                    continue
                conn.execute(
                    """
                    INSERT OR REPLACE INTO task_outcomes(outcome_id, date, plan_id, business_date, run_mode, session_id, is_canonical, revision, superseded_at,
                                                         title, subject, status, evidence, reason, remaining, corrected, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?, NULL, ?, ?, ?, ?, ?, ?, 0, ?)
                    """,
                    (
                        outcome_id,
                        date,
                        plan_id,
                        context["business_date"],
                        context["run_mode"],
                        context["session_id"],
                        revision,
                        item["title"],
                        item.get("subject", "other"),
                        item["status"],
                        item.get("evidence", ""),
                        item.get("reason", ""),
                        item.get("remaining", ""),
                        now,
                    ),
                )
            conn.execute("DELETE FROM pending_tasks WHERE date = ? AND plan_id = ? AND run_mode = ? AND session_id = ? AND status = 'open'", (date, plan_id, context["run_mode"], context["session_id"]))
            for task in daily_log.get("pending_tasks_added", []) or []:
                title = str(task.get("title", "")).strip()
                if not title:
                    continue
                pending_id = self._pending_id(date, plan_id, title, context["run_mode"], context["session_id"])
                conn.execute(
                    """
                    INSERT OR REPLACE INTO pending_tasks(pending_id, date, plan_id, business_date, run_mode, session_id, is_canonical, revision, superseded_at,
                                                         title, subject, reason, suggested_next_step, status, source_date, created_at, resolved_at)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?, NULL, ?, ?, ?, ?, 'open', ?, ?, NULL)
                    """,
                    (
                        pending_id,
                        date,
                        plan_id,
                        context["business_date"],
                        context["run_mode"],
                        context["session_id"],
                        revision,
                        title,
                        task.get("subject", "other"),
                        task.get("reason", ""),
                        task.get("suggested_next_step", ""),
                        date,
                        now,
                    ),
                )
            self._recalculate_completion(conn, date, plan_id, context["run_mode"], context["session_id"])

    def add_parent_correction(self, date: str, payload: dict[str, Any], *, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> dict[str, Any]:
        now = now_iso()
        context = self._normalize_context(date, run_mode, session_id)
        target_type = str(payload.get("target_type", "task_outcome"))
        target_id = str(payload.get("target_id", "")).strip()
        field = str(payload.get("field", "status"))
        new_value = str(payload.get("new_value", ""))
        old_value = str(payload.get("old_value", ""))
        reason = str(payload.get("reason", ""))
        correction_id = f"corr_{date}_{target_type}_{target_id}_{field}_{datetime.now().strftime('%H%M%S%f')}"
        with self._connect() as conn:
            outcome_row = None
            if target_type == "task_outcome" and field == "status":
                outcome_row = conn.execute(
                    "SELECT * FROM task_outcomes WHERE date = ? AND run_mode = ? AND session_id = ? AND (outcome_id = ? OR title = ?) ORDER BY updated_at DESC LIMIT 1",
                    (date, context["run_mode"], context["session_id"], target_id, target_id),
                ).fetchone()
                if outcome_row and not old_value:
                    old_value = str(outcome_row["status"])
            conn.execute(
                """
                INSERT INTO parent_corrections(correction_id, date, business_date, run_mode, session_id, is_canonical, revision, superseded_at,
                                               target_type, target_id, field, old_value, new_value, reason, created_at)
                VALUES (?, ?, ?, ?, ?, 1, 1, NULL, ?, ?, ?, ?, ?, ?, ?)
                """,
                (correction_id, date, context["business_date"], context["run_mode"], context["session_id"], target_type, target_id, field, old_value, new_value, reason, now),
            )
            if outcome_row:
                conn.execute(
                    "UPDATE task_outcomes SET status = ?, corrected = 1, reason = ?, updated_at = ? WHERE outcome_id = ?",
                    (new_value, reason, now, outcome_row["outcome_id"]),
                )
                self._sync_pending_after_status_correction(conn, date, outcome_row["plan_id"], outcome_row["title"], outcome_row["subject"], new_value, reason, now, run_mode=context["run_mode"], session_id=context["session_id"], business_date=context["business_date"], revision=int(outcome_row["revision"] or 1))
                self._recalculate_completion(conn, date, outcome_row["plan_id"], context["run_mode"], context["session_id"])
            if context["run_mode"] == RUN_MODE_OFFICIAL:
                self._sync_profile_after_correction(conn, date, target_type, target_id, field, old_value, new_value, reason)
        return {
            "correction_id": correction_id,
            "date": date,
            "business_date": context["business_date"],
            "run_mode": context["run_mode"],
            "session_id": context["session_id"],
            "target_type": target_type,
            "target_id": target_id,
            "field": field,
            "old_value": old_value,
            "new_value": new_value,
            "reason": reason,
            "created_at": now,
        }

    def resolve_pending_task(self, pending_id: str) -> dict[str, Any]:
        now = now_iso()
        with self._connect() as conn:
            conn.execute(
                "UPDATE pending_tasks SET status = 'resolved', resolved_at = ? WHERE pending_id = ?",
                (now, pending_id),
            )
            row = conn.execute("SELECT * FROM pending_tasks WHERE pending_id = ?", (pending_id,)).fetchone()
        if not row:
            raise ValueError("pending task not found")
        return dict(row)

    def list_pending_tasks(self, status: str | None = None, *, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> list[dict[str, Any]]:
        sql = "SELECT * FROM pending_tasks WHERE run_mode = ? AND is_canonical = 1"
        params: list[Any] = [run_mode]
        if session_id:
            sql += " AND session_id = ?"
            params.append(session_id)
        if status:
            sql += " AND status = ?"
            params.append(status)
        sql += " ORDER BY created_at DESC"
        with self._connect() as conn:
            return [dict(row) for row in conn.execute(sql, params).fetchall()]

    def get_day(self, date: str, *, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> dict[str, Any]:
        context = self._normalize_context(date, run_mode, session_id)
        with self._connect() as conn:
            plan_row = conn.execute(
                "SELECT * FROM daily_plans WHERE business_date = ? AND run_mode = ? AND session_id = ? AND is_canonical = 1 ORDER BY updated_at DESC LIMIT 1",
                (context["business_date"], context["run_mode"], context["session_id"]),
            ).fetchone()
            log_row = conn.execute(
                "SELECT * FROM daily_logs WHERE business_date = ? AND run_mode = ? AND session_id = ? AND is_canonical = 1 ORDER BY updated_at DESC LIMIT 1",
                (context["business_date"], context["run_mode"], context["session_id"]),
            ).fetchone()
            active_row = log_row or plan_row
            plan_id = active_row["plan_id"] if active_row else None
            outcomes = []
            pending = []
            if plan_id:
                outcomes = [dict(row) for row in conn.execute("SELECT * FROM task_outcomes WHERE business_date = ? AND plan_id = ? AND run_mode = ? AND session_id = ? ORDER BY title", (context["business_date"], plan_id, context["run_mode"], context["session_id"])).fetchall()]
                pending = [dict(row) for row in conn.execute("SELECT * FROM pending_tasks WHERE business_date = ? AND plan_id = ? AND run_mode = ? AND session_id = ? ORDER BY created_at DESC", (context["business_date"], plan_id, context["run_mode"], context["session_id"])).fetchall()]
            corrections = [dict(row) for row in conn.execute("SELECT * FROM parent_corrections WHERE business_date = ? AND run_mode = ? AND session_id = ? ORDER BY created_at DESC", (context["business_date"], context["run_mode"], context["session_id"])).fetchall()]
            profile_snapshots = [dict(row) for row in conn.execute("SELECT * FROM profile_snapshots ORDER BY created_at DESC LIMIT 5").fetchall()]
        return {
            "date": date,
            "business_date": context["business_date"],
            "run_mode": context["run_mode"],
            "session_id": context["session_id"],
            "plan": self._plan_from_row(plan_row),
            "daily_log": self._log_from_row(log_row),
            "task_outcomes": outcomes,
            "pending_tasks": pending,
            "corrections": corrections,
            "profile_snapshots": profile_snapshots,
        }

    def get_history(self, date_from: str, date_to: str, *, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> dict[str, Any]:
        params: list[Any] = [date_from, date_to, run_mode]
        session_filter = ""
        if session_id:
            session_filter = "AND session_id = ?"
            params.append(session_id)
        with self._connect() as conn:
            rows = conn.execute(
                f"""
                SELECT business_date AS date, completion_rate, energy_level, summary, encouragement, run_mode, session_id, revision, is_canonical
                FROM daily_logs
                WHERE business_date >= ? AND business_date <= ? AND run_mode = ? AND is_canonical = 1 {session_filter}
                ORDER BY business_date ASC
                """,
                params,
            ).fetchall()
        return {"from": date_from, "to": date_to, "days": [dict(row) for row in rows]}

    def get_overview(self, *, today: str | None = None, days: int = 7, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> dict[str, Any]:
        current = today or today_str()
        date_from = (datetime.strptime(current, "%Y-%m-%d") - timedelta(days=days - 1)).strftime("%Y-%m-%d")
        history = self.get_history(date_from, current, run_mode=run_mode, session_id=session_id)["days"]
        rates = [float(item["completion_rate"]) for item in history if item.get("completion_rate") is not None]
        average = round(sum(rates) / len(rates), 2) if rates else 0.0
        pending = self.list_pending_tasks(status="open", run_mode=run_mode, session_id=session_id)
        weak_points: list[str] = []
        with self._connect() as conn:
            params: list[Any] = [date_from, current, run_mode]
            session_filter = ""
            if session_id:
                session_filter = "AND session_id = ?"
                params.append(session_id)
            rows = conn.execute(f"SELECT raw_json FROM daily_logs WHERE business_date >= ? AND business_date <= ? AND run_mode = ? AND is_canonical = 1 {session_filter}", params).fetchall()
            for row in rows:
                weak_points.extend(_loads(row["raw_json"], {}).get("new_weaknesses", []) or [])
        return {
            "today": self.get_day(current, run_mode=run_mode, session_id=session_id),
            "recent": {
                "from": date_from,
                "to": current,
                "average_completion_rate": average,
                "days_with_logs": len(history),
                "weak_points": weak_points[:8],
            },
            "pending_count": len(pending),
            "pending_tasks": pending[:5],
        }

    def record_profile_snapshot(self, path: str | Path, *, reason: str = "") -> None:
        snapshot_id = f"profile_{datetime.now().strftime('%Y%m%d_%H%M%S%f')}"
        with self._connect() as conn:
            conn.execute(
                "INSERT INTO profile_snapshots(snapshot_id, path, created_at, reason) VALUES (?, ?, ?, ?)",
                (snapshot_id, str(path), now_iso(), reason),
            )

    def create_debug_session(self, *, business_date: str | None = None) -> dict[str, Any]:
        date = business_date or today_str()
        session_id = f"debug:{date}:{datetime.now().strftime('%Y%m%d_%H%M%S%f')}"
        return {"session_id": session_id, "business_date": date, "run_mode": RUN_MODE_DEBUG}

    def list_debug_sessions(self) -> list[dict[str, Any]]:
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT session_id, business_date, MAX(updated_at) AS updated_at, COUNT(*) AS record_count
                FROM (
                    SELECT session_id, business_date, updated_at FROM daily_plans WHERE run_mode = 'debug'
                    UNION ALL
                    SELECT session_id, business_date, updated_at FROM daily_logs WHERE run_mode = 'debug'
                )
                WHERE session_id IS NOT NULL
                GROUP BY session_id, business_date
                ORDER BY updated_at DESC
                """
            ).fetchall()
        return [{"run_mode": RUN_MODE_DEBUG, **dict(row)} for row in rows]

    def get_debug_session(self, session_id: str) -> dict[str, Any]:
        with self._connect() as conn:
            row = conn.execute(
                """
                SELECT business_date FROM daily_logs WHERE run_mode = 'debug' AND session_id = ?
                UNION
                SELECT business_date FROM daily_plans WHERE run_mode = 'debug' AND session_id = ?
                LIMIT 1
                """,
                (session_id, session_id),
            ).fetchone()
        if not row:
            raise ValueError("debug session not found")
        return {"session_id": session_id, "run_mode": RUN_MODE_DEBUG, "business_date": row["business_date"], "day": self.get_day(row["business_date"], run_mode=RUN_MODE_DEBUG, session_id=session_id)}

    def clear_debug_sessions(self) -> dict[str, Any]:
        with self._connect() as conn:
            counts = {}
            for table in ["daily_plans", "daily_logs", "task_outcomes", "pending_tasks", "parent_corrections"]:
                counts[table] = conn.execute(f"SELECT COUNT(*) FROM {table} WHERE run_mode = 'debug'").fetchone()[0]
                conn.execute(f"DELETE FROM {table} WHERE run_mode = 'debug'")
        return {"cleared": True, "counts": counts}

    def accept_debug_session_as_official(self, session_id: str) -> dict[str, Any]:
        debug = self.get_debug_session(session_id)
        date = debug["business_date"]
        day = debug["day"]
        if not day.get("plan") and not day.get("daily_log"):
            raise ValueError("debug session has no data to accept")
        if day.get("plan"):
            raw_plan = dict(day["plan"].get("raw") or {})
            raw_plan.update(
                {
                    "date": date,
                    "plan_id": f"official:{date}",
                    "plan_title": day["plan"].get("plan_title"),
                    "total_minutes": day["plan"].get("total_minutes"),
                    "tasks": day["plan"].get("tasks", []),
                }
            )
            self.upsert_plan(raw_plan, source="accepted_debug", run_mode=RUN_MODE_OFFICIAL)
        if day.get("daily_log"):
            raw_log = dict(day["daily_log"].get("raw") or {})
            raw_log.update(
                {
                    "date": date,
                    "plan_id": f"official:{date}",
                    "summary": day["daily_log"].get("summary", ""),
                    "completion_rate": day["daily_log"].get("completion_rate", 0),
                    "energy_level": day["daily_log"].get("energy_level", "unknown"),
                    "mood_signal": day["daily_log"].get("mood_signal", ""),
                    "source_input": day["daily_log"].get("source_input", ""),
                }
            )
            self.upsert_daily_log(raw_log, run_mode=RUN_MODE_OFFICIAL)
        return {"accepted": True, "session_id": session_id, "business_date": date, "day": self.get_day(date)}

    def _sync_pending_after_status_correction(
        self,
        conn: sqlite3.Connection,
        date: str,
        plan_id: str,
        title: str,
        subject: str | None,
        status: str,
        reason: str,
        now: str,
        *,
        run_mode: str,
        session_id: str,
        business_date: str,
        revision: int,
    ) -> None:
        pending_id = self._pending_id(date, plan_id, title, run_mode, session_id)
        if status == "completed":
            conn.execute(
                "UPDATE pending_tasks SET status = 'resolved', resolved_at = ? WHERE pending_id = ? AND status = 'open'",
                (now, pending_id),
            )
            return

        next_step = "明天只接住一个最小动作。"
        if status == "partial":
            next_step = "明天轻量补上剩余一步。"
        conn.execute(
            """
            INSERT INTO pending_tasks(pending_id, date, plan_id, business_date, run_mode, session_id, is_canonical, revision, superseded_at,
                                      title, subject, reason, suggested_next_step, status, source_date, created_at, resolved_at)
            VALUES (?, ?, ?, ?, ?, ?, 1, ?, NULL, ?, ?, ?, ?, 'open', ?, ?, NULL)
            ON CONFLICT(pending_id) DO UPDATE SET
                reason = excluded.reason,
                suggested_next_step = excluded.suggested_next_step,
                status = 'open',
                resolved_at = NULL
            """,
            (pending_id, date, plan_id, business_date, run_mode, session_id, revision, title, subject or "other", reason or "家长纠偏后仍需轻量跟进", next_step, date, now),
        )

    def _sync_profile_after_correction(
        self,
        conn: sqlite3.Connection,
        date: str,
        target_type: str,
        target_id: str,
        field: str,
        old_value: str,
        new_value: str,
        reason: str,
    ) -> None:
        profile = load_student_profile(self.config)
        profile.setdefault("profile_version", "2.0")
        profile.setdefault("student", {"nickname": "小航", "grade": "六年级", "stage": "小升初过渡期"})
        profile.setdefault("family_goal", {})
        profile.setdefault("subjects", {})
        profile.setdefault("weekly_schedule", [])
        profile.setdefault("burden_rules", {})
        profile.setdefault("pending_tasks", [])
        profile.setdefault("learning_history", [])
        profile.setdefault("energy_trend", [])
        profile.setdefault("procrastination_signals", [])

        log_row = conn.execute("SELECT completion_rate, energy_level FROM daily_logs WHERE date = ? ORDER BY updated_at DESC LIMIT 1", (date,)).fetchone()
        summary = f"家长纠偏：{target_id} 的 {field} 从 {old_value or 'unknown'} 调整为 {new_value}。"
        if reason:
            summary += f" 原因：{reason}"
        profile["learning_history"].append(
            {
                "date": date,
                "summary": summary,
                "completion_rate": float(log_row["completion_rate"]) if log_row and log_row["completion_rate"] is not None else None,
                "energy_level": log_row["energy_level"] if log_row else "unknown",
                "new_weaknesses": [],
                "source": "parent_correction",
            }
        )

        if target_type == "task_outcome" and field == "status":
            self._sync_profile_pending_tasks(profile, date, target_id, new_value, reason)

        rag_summary = str(profile.get("rag_summary", "")).strip()
        correction_line = f"家长纠偏记录：{date}，{target_id} 从 {old_value or 'unknown'} 调整为 {new_value}。"
        profile["rag_summary"] = (rag_summary + "\n" + correction_line).strip() if rag_summary else correction_line
        markdown = profile_to_markdown(profile)
        chunks = build_chunks_from_profile(profile, markdown)
        save_student_profile(profile, self.config)
        save_student_profile_markdown(markdown, self.config)
        save_rag_chunks(chunks, self.config)

        snapshot_id = f"profile_{datetime.now().strftime('%Y%m%d_%H%M%S%f')}"
        conn.execute(
            "INSERT INTO profile_snapshots(snapshot_id, path, created_at, reason) VALUES (?, ?, ?, ?)",
            (snapshot_id, str(self.config.student_profile_json_path), now_iso(), "parent_correction"),
        )

    def _sync_profile_pending_tasks(self, profile: dict[str, Any], date: str, title: str, status: str, reason: str) -> None:
        pending = profile.setdefault("pending_tasks", [])
        if status == "completed":
            for task in pending:
                if str(task.get("title", "")) == title:
                    task["status"] = "resolved_by_parent_correction"
                    task["resolved_at"] = now_iso()
            return

        for task in pending:
            if str(task.get("title", "")) == title:
                task["reason"] = reason or task.get("reason", "家长纠偏后仍需轻量跟进")
                task["suggested_next_step"] = task.get("suggested_next_step") or "明天只做一个最小步骤。"
                task["status"] = "open"
                return
        pending.append(
            {
                "task_id": f"pending_parent_{date}_{len(pending) + 1}",
                "title": title,
                "subject": "other",
                "reason": reason or "家长纠偏后仍需轻量跟进",
                "suggested_next_step": "明天只做一个最小步骤。",
                "priority": "medium",
                "created_at": now_iso(),
                "source": "parent_correction",
                "status": "open",
            }
        )

    def _plan_from_row(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if not row:
            return None
        data = dict(row)
        data["tasks"] = _loads(data.pop("tasks_json", None), [])
        data["raw"] = _loads(data.pop("raw_json", None), {})
        return data

    def _log_from_row(self, row: sqlite3.Row | None) -> dict[str, Any] | None:
        if not row:
            return None
        data = dict(row)
        data["raw"] = _loads(data.pop("raw_json", None), {})
        return data

    def _task_items(self, daily_log: dict[str, Any]) -> list[dict[str, Any]]:
        items: list[dict[str, Any]] = []
        for status, key in [("completed", "completed"), ("partial", "partially_completed"), ("missed", "not_completed")]:
            for task in daily_log.get(key, []) or []:
                title = str(task.get("title", "")).strip()
                if not title:
                    continue
                items.append({**task, "title": title, "status": status})
        return items

    def _has_corrected_outcome(self, conn: sqlite3.Connection, outcome_id: str) -> bool:
        row = conn.execute("SELECT corrected FROM task_outcomes WHERE outcome_id = ?", (outcome_id,)).fetchone()
        return bool(row and row["corrected"])

    def _recalculate_completion(self, conn: sqlite3.Connection, date: str, plan_id: str, run_mode: str, session_id: str) -> None:
        rows = conn.execute("SELECT status FROM task_outcomes WHERE date = ? AND plan_id = ? AND run_mode = ? AND session_id = ?", (date, plan_id, run_mode, session_id)).fetchall()
        if not rows:
            return
        score = sum(1.0 if row["status"] == "completed" else 0.5 if row["status"] == "partial" else 0.0 for row in rows)
        rate = round(score / len(rows), 2)
        conn.execute("UPDATE daily_logs SET completion_rate = ?, updated_at = ? WHERE date = ? AND plan_id = ? AND run_mode = ? AND session_id = ?", (rate, now_iso(), date, plan_id, run_mode, session_id))

    def _outcome_id(self, date: str, plan_id: str, title: str, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> str:
        return f"{run_mode}::{session_id or f'official:{date}'}::{date}::{plan_id}::{title}"

    def _pending_id(self, date: str, plan_id: str, title: str, run_mode: str = RUN_MODE_OFFICIAL, session_id: str | None = None) -> str:
        return f"{run_mode}::{session_id or f'official:{date}'}::{date}::{plan_id}::{title}"
