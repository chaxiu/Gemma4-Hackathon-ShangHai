from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime

from backend.storage import today_str

RUN_MODE_OFFICIAL = "official"
RUN_MODE_DEBUG = "debug"


@dataclass(frozen=True)
class RunContext:
    run_mode: str = RUN_MODE_OFFICIAL
    session_id: str | None = None
    business_date: str | None = None

    def normalized_date(self) -> str:
        return self.business_date or today_str()

    def normalized_session_id(self) -> str:
        date = self.normalized_date()
        if self.session_id:
            return self.session_id
        if self.run_mode == RUN_MODE_DEBUG:
            return f"debug:{date}:{datetime.now().strftime('%Y%m%d_%H%M%S%f')}"
        return f"official:{date}"


def normalize_run_mode(value: str | None) -> str:
    return RUN_MODE_DEBUG if value == RUN_MODE_DEBUG else RUN_MODE_OFFICIAL
