"""Versioned prompt assets (docs/03-technical-spec.md Section 7.5, 9.5).

A prompt lives on disk, not in Python source, under::

    <prompts_dir>/<name>/v<version>/system.txt
    <prompts_dir>/<name>/v<version>/user.txt

``user.txt`` is a ``string.Template`` (``${variable}`` placeholders); ``system.txt``
is used verbatim. Replacing a prompt is an edit to these files plus a version bump
in the directory name and in the capability that loads it — no code change to any
provider.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from string import Template
from typing import Any


@dataclass(frozen=True, slots=True)
class RenderedPrompt:
    system: str
    user: str


@dataclass(frozen=True, slots=True)
class Prompt:
    name: str
    version: int
    system: str
    user_template: str

    @property
    def qualified_version(self) -> str:
        """The identifier echoed back to the caller, e.g. ``echo/v1``."""
        return f"{self.name}/v{self.version}"

    def render(self, **variables: Any) -> RenderedPrompt:
        user = Template(self.user_template).substitute(
            {key: str(value) for key, value in variables.items()}
        )
        return RenderedPrompt(system=self.system, user=user)


class PromptLibrary:
    """Loads and caches prompt assets from one directory."""

    def __init__(self, prompts_dir: Path) -> None:
        self._dir = prompts_dir
        self._cache: dict[tuple[str, int], Prompt] = {}

    def load(self, name: str, version: int) -> Prompt:
        key = (name, version)
        cached = self._cache.get(key)
        if cached is not None:
            return cached

        base = self._dir / name / f"v{version}"
        try:
            system = (base / "system.txt").read_text(encoding="utf-8").strip()
            user_template = (base / "user.txt").read_text(encoding="utf-8")
        except FileNotFoundError as missing:
            raise FileNotFoundError(
                f"prompt '{name}/v{version}' not found under {self._dir}"
            ) from missing

        prompt = Prompt(name=name, version=version, system=system, user_template=user_template)
        self._cache[key] = prompt
        return prompt
