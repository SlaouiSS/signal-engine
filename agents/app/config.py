"""Runtime configuration for the AI capability service.

12-factor style (docs/03-technical-spec.md Section 15): every value comes from an
environment variable with a non-secret local-development default. No secret is
read here — a provider's own adapter reads its credential (e.g. NVIDIA_API_KEY)
from its own environment variable, never committed (docs/adr/0006).

Final provider decision: the generative LLM is NVIDIA Build; Ollama is kept only
for embeddings (embeddinggemma). "ollama" remains a selectable value for
``AGENTS_LLM_PROVIDER`` — the adapter is unchanged — but is no longer the default.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

_DEFAULT_PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


@dataclass(frozen=True, slots=True)
class Settings:
    """Immutable, explicitly constructed settings object."""

    host: str
    port: int
    log_level: str

    # Inbound HTTP request-body limit (docs/10-security.md Section 10, 12). Larger
    # than the exposed Java API's limit because a capability payload legitimately
    # carries source content — above the 5 MiB ingestion response cap plus JSON
    # overhead. Provisional default open to tuning (docs/03-technical-spec.md T9).
    max_request_bytes: int

    # AI capability foundation (docs/03-technical-spec.md Section 7-9;
    # docs/adr/0006-ai-java-python-foundation.md).
    llm_provider: str
    ollama_url: str
    ollama_model: str
    # NVIDIA Build (OpenAI-compatible) — the "NVIDIA first, Ollama later" option
    # for semantic-chunking development (docs/adr/0010). The key is a secret with
    # an empty default; the adapter fails clearly if it is selected without one.
    nvidia_base_url: str
    nvidia_model: str
    nvidia_api_key: str
    llm_request_timeout_seconds: float
    # Embedding path (docs/06-ai-agents.md Section 4.6; docs/adr/0011). The model
    # is provisional until T3 is fixed; the Task 8.3A benchmark recommends one.
    embedding_provider: str
    ollama_embedding_model: str
    embedding_request_timeout_seconds: float
    prompts_dir: Path

    @staticmethod
    def from_env() -> Settings:
        return Settings(
            # Localhost binding by default (docs/03-technical-spec.md Section 20.1);
            # the container overrides this with AGENTS_HOST=0.0.0.0.
            host=os.environ.get("AGENTS_HOST", "127.0.0.1"),
            port=int(os.environ.get("AGENTS_PORT", "8100")),
            log_level=os.environ.get("LOG_LEVEL", "info"),
            max_request_bytes=int(os.environ.get("AGENTS_MAX_REQUEST_BYTES", str(8 * 1024 * 1024))),
            # "nvidia" is the decided generative-LLM provider for the actual deployed
            # stack — docker-compose.yml and .env.example both set
            # AGENTS_LLM_PROVIDER=nvidia explicitly (docs/adr/0006). The fallback here
            # (when the variable is entirely unset, e.g. running bare with no .env
            # sourced) stays "ollama": unlike NvidiaLlmProvider, OllamaLlmProvider
            # validates nothing at construction time, so an unconfigured environment
            # — notably every test run, none of which sources .env or sets
            # NVIDIA_API_KEY — can still import and construct the app. "fake" selects
            # the deterministic scripted provider for a running stack without a model
            # (tests construct their own provider directly, bypassing this default
            # entirely).
            llm_provider=os.environ.get("AGENTS_LLM_PROVIDER", "ollama"),
            # Only used if AGENTS_LLM_PROVIDER is switched back to "ollama" — not
            # required by default. ollama_url is also shared with the embedding
            # provider below, which does default to "ollama".
            ollama_url=os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434"),
            ollama_model=os.environ.get("OLLAMA_MODEL", "gpt-oss:20b"),
            # Provisional model id — not a benchmarked choice
            # (docs/07-rag.md Section 21).
            nvidia_base_url=os.environ.get(
                "NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1"
            ),
            nvidia_model=os.environ.get("NVIDIA_MODEL", "nvidia/nemotron-3-super-120b-a12b"),
            nvidia_api_key=os.environ.get("NVIDIA_API_KEY", ""),
            llm_request_timeout_seconds=float(
                os.environ.get("AGENTS_LLM_REQUEST_TIMEOUT_SECONDS", "60")
            ),
            embedding_provider=os.environ.get("AGENTS_EMBEDDING_PROVIDER", "ollama"),
            # Provisional default — the model the Task 8.3A benchmark recommends
            # (embeddinggemma, 768-dim; docs/07-rag.md Section 22,
            # docs/adr/0011). T3 stays formally open pending validation on real
            # ingested content; snowflake-arctic-embed2 is the documented fallback.
            ollama_embedding_model=os.environ.get("OLLAMA_EMBEDDING_MODEL", "embeddinggemma"),
            embedding_request_timeout_seconds=float(
                os.environ.get("EMBEDDING_REQUEST_TIMEOUT_SECONDS", "30")
            ),
            prompts_dir=Path(os.environ.get("AGENTS_PROMPTS_DIR", str(_DEFAULT_PROMPTS_DIR))),
        )
