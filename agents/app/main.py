"""Application composition entry point.

Dependencies are composed explicitly here — the module that builds the FastAPI app
(docs/04-architecture.md Section 5.2). No hidden registry, no service locator, no
DI container. Provider selection is configuration (``AGENTS_LLM_PROVIDER``);
capability handlers receive their collaborators as plain arguments.
"""

from __future__ import annotations

import logging

import uvicorn
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.api import capabilities, health
from app.capabilities.answer import AnswerCapability
from app.capabilities.base import Capability
from app.capabilities.echo import EchoCapability
from app.capabilities.embed import EmbedCapability
from app.capabilities.importance import ImportanceCapability
from app.capabilities.near_duplicate import NearDuplicateCapability
from app.capabilities.relevance import RelevanceCapability
from app.capabilities.semantic_chunk_boundary import SemanticChunkBoundaryCapability
from app.capabilities.summarize import SummarizeCapability
from app.config import Settings
from app.contract import ErrorCategory, failure
from app.errors import CapabilityError
from app.http_limits import RequestBodySizeLimitMiddleware
from app.prompts import PromptLibrary
from app.providers.base import EmbeddingProvider, LlmProvider
from app.providers.nvidia import NvidiaLlmProvider
from app.providers.ollama import OllamaEmbeddingProvider, OllamaLlmProvider
from app.providers.scripted import ScriptedLlmProvider

_CORRELATION_HEADER = "X-Correlation-Id"
_DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024
_logger = logging.getLogger("app.capabilities")


def build_provider(settings: Settings) -> LlmProvider:
    if settings.llm_provider == "ollama":
        return OllamaLlmProvider(base_url=settings.ollama_url, model=settings.ollama_model)
    if settings.llm_provider == "nvidia":
        return NvidiaLlmProvider(
            base_url=settings.nvidia_base_url,
            model=settings.nvidia_model,
            api_key=settings.nvidia_api_key,
        )
    if settings.llm_provider == "fake":
        return ScriptedLlmProvider.from_env()
    raise ValueError(f"unknown AGENTS_LLM_PROVIDER '{settings.llm_provider}'")


def build_embedding_provider(settings: Settings) -> EmbeddingProvider:
    if settings.embedding_provider == "ollama":
        return OllamaEmbeddingProvider(
            base_url=settings.ollama_url, model=settings.ollama_embedding_model
        )
    raise ValueError(f"unknown AGENTS_EMBEDDING_PROVIDER '{settings.embedding_provider}'")


def build_capabilities(
    settings: Settings,
    provider: LlmProvider,
    embedding_provider: EmbeddingProvider,
) -> dict[str, Capability]:
    prompts = PromptLibrary(settings.prompts_dir)
    timeout = settings.llm_request_timeout_seconds
    registered: list[Capability] = [
        EchoCapability(provider=provider, prompts=prompts, timeout_seconds=timeout),
        NearDuplicateCapability(provider=provider, prompts=prompts, timeout_seconds=timeout),
        RelevanceCapability(provider=provider, prompts=prompts, timeout_seconds=timeout),
        ImportanceCapability(provider=provider, prompts=prompts, timeout_seconds=timeout),
        SummarizeCapability(provider=provider, prompts=prompts, timeout_seconds=timeout),
        AnswerCapability(provider=provider, prompts=prompts, timeout_seconds=timeout),
        SemanticChunkBoundaryCapability(
            provider=provider, prompts=prompts, timeout_seconds=timeout
        ),
        EmbedCapability(
            provider=embedding_provider,
            timeout_seconds=settings.embedding_request_timeout_seconds,
        ),
    ]
    return {capability.name: capability for capability in registered}


def create_app(
    capability_registry: dict[str, Capability] | None = None,
    *,
    max_request_bytes: int = _DEFAULT_MAX_REQUEST_BYTES,
) -> FastAPI:
    app = FastAPI(
        title="Signal Engine AI Capability Service",
        version="0.1.0",
        summary="Stateless AI/NLP capabilities behind explicit contracts.",
    )

    if capability_registry is None:
        settings = Settings.from_env()
        max_request_bytes = settings.max_request_bytes
        capability_registry = build_capabilities(
            settings, build_provider(settings), build_embedding_provider(settings)
        )
    app.state.capabilities = capability_registry

    app.add_middleware(RequestBodySizeLimitMiddleware, max_request_bytes=max_request_bytes)
    app.include_router(health.router)
    app.include_router(capabilities.router)
    _install_error_handlers(app)
    return app


def _json_safe_validation_errors(exc: RequestValidationError) -> list[dict[str, object]]:
    """A JSON-serialisable view of the request-validation failures.

    Pydantic includes the raw offending ``input`` in each error entry. When the
    body is not a JSON object — a wrong ``Content-Type``, a non-JSON payload —
    that input is the raw request ``bytes``, which are not JSON-serialisable.
    Passing ``exc.errors()`` straight through made rendering the error envelope
    itself fail, so a malformed client request became a retryable HTTP 500
    instead of the intended ``AI_REQUEST_INVALID`` / 400. Only the structured
    fields (``type``, ``loc``, ``msg``, and a stringified ``ctx``) are kept; the
    echoed input is dropped.
    """
    safe: list[dict[str, object]] = []
    for error in exc.errors():
        entry: dict[str, object] = {
            "type": str(error.get("type", "")),
            "loc": [str(part) for part in error.get("loc", ())],
            "msg": str(error.get("msg", "")),
        }
        ctx = error.get("ctx")
        if isinstance(ctx, dict) and ctx:
            entry["ctx"] = {str(key): str(value) for key, value in ctx.items()}
        safe.append(entry)
    return safe


def _install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    def _on_request_validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        return _error_response(
            request,
            status=400,
            code="AI_REQUEST_INVALID",
            category=ErrorCategory.REQUEST,
            retryable=False,
            message="the request envelope is malformed",
            details={"errors": _json_safe_validation_errors(exc)},
        )

    @app.exception_handler(CapabilityError)
    def _on_capability_error(request: Request, exc: CapabilityError) -> JSONResponse:
        return _error_response(
            request,
            status=exc.http_status,
            code=exc.code,
            category=exc.category,
            retryable=exc.retryable,
            message=exc.message,
            details=exc.details,
        )

    @app.exception_handler(Exception)
    def _on_unexpected(request: Request, exc: Exception) -> JSONResponse:
        _logger.exception("unhandled error in capability call")
        return _error_response(
            request,
            status=500,
            code="AI_INTERNAL",
            category=ErrorCategory.INTERNAL,
            retryable=True,
            message="internal error",
            details=None,
        )


def _error_response(
    request: Request,
    *,
    status: int,
    code: str,
    category: ErrorCategory,
    retryable: bool,
    message: str,
    details: dict[str, object] | None,
) -> JSONResponse:
    correlation_id = request.headers.get(_CORRELATION_HEADER) or "unknown"
    capability = request.path_params.get("name", "unknown")
    body = failure(
        correlation_id=correlation_id,
        capability=str(capability),
        code=code,
        category=category,
        retryable=retryable,
        message=message,
        details=details,
    )
    return JSONResponse(status_code=status, content=body.model_dump(by_alias=True))


app = create_app()


def main() -> None:
    settings = Settings.from_env()
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level,
    )


if __name__ == "__main__":
    main()
