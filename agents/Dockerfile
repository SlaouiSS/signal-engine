# Signal Engine AI capability service image (docs/03-technical-spec.md Section 4.10, 17.2).
# Python 3.13 (D3), dependencies installed from the committed uv.lock.

FROM python:3.13-slim AS runtime
WORKDIR /app

# uv is the single Python project/dependency manager (D21). Pinned to an explicit
# release for reproducible builds (docs/03-technical-spec.md Section 19.3, 20.3);
# Dependabot (docker ecosystem) proposes bumps.
COPY --from=ghcr.io/astral-sh/uv:0.11.29 /uv /uvx /bin/

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

# Install locked dependencies first for layer caching.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-install-project --no-dev

COPY app ./app
COPY prompts ./prompts
RUN uv sync --frozen --no-dev

RUN useradd --system signalengine
USER signalengine

EXPOSE 8100
ENV AGENTS_HOST=0.0.0.0 \
    AGENTS_PROMPTS_DIR=/app/prompts \
    PATH="/app/.venv/bin:$PATH"
ENTRYPOINT ["python", "-m", "app.main"]
