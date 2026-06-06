from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.run_parent_frontend import PARENT_FRONTEND, resolve_npm_command


def test_parent_frontend_points_to_parent_package() -> None:
    assert PARENT_FRONTEND == ROOT / "parent-frontend"


def test_resolve_npm_command_prefers_windows_cmd(monkeypatch) -> None:
    def fake_which(command: str) -> str | None:
        if command == "npm.cmd":
            return r"C:\Program Files\nodejs\npm.cmd"
        if command == "npm":
            return None
        return None

    monkeypatch.setattr("scripts.run_parent_frontend.shutil.which", fake_which)

    assert resolve_npm_command() == r"C:\Program Files\nodejs\npm.cmd"


def test_resolve_npm_command_falls_back_to_npm(monkeypatch) -> None:
    def fake_which(command: str) -> str | None:
        if command == "npm":
            return "/usr/local/bin/npm"
        return None

    monkeypatch.setattr("scripts.run_parent_frontend.shutil.which", fake_which)

    assert resolve_npm_command() == "/usr/local/bin/npm"
