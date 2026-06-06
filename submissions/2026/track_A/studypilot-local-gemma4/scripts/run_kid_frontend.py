from __future__ import annotations

import subprocess
import sys
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KID_FRONTEND = ROOT / "kid-frontend"


def resolve_npm_command() -> str:
    return shutil.which("npm.cmd") or shutil.which("npm") or "npm"


def main() -> int:
    npm = resolve_npm_command()
    if not (KID_FRONTEND / "node_modules").exists():
        install_code = subprocess.call([npm, "install"], cwd=str(KID_FRONTEND))
        if install_code != 0:
            return install_code
    return subprocess.call([npm, "run", "dev"], cwd=str(KID_FRONTEND))


if __name__ == "__main__":
    raise SystemExit(main())
