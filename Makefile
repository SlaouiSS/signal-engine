# Signal Engine — developer entry points (docs/03-technical-spec.md Section 17.3).
#
# Contributors without `make` (e.g. on Windows) can run the underlying commands
# directly; see README.md. `seed` registers the initial curated source set
# (scripts/seed-sources.sh) against a running backend — explicit and opt-in, not
# a Flyway migration, since the concrete source list remains an open product
# decision (docs/02-functional-spec.md Q1). Schema migrations (Flyway V1–V11)
# were introduced in Phase 2 and run automatically on backend startup — see the
# `migrate` target below.

.PHONY: up down logs build test lint format migrate seed

up: ## Start the full local system via Docker Compose
	docker compose up --build -d

down: ## Stop the local system
	docker compose down

logs: ## Follow logs from all services
	docker compose logs -f

build: ## Build all three sub-projects
	cd backend && ./gradlew build
	cd agents && uv sync && uv build
	cd frontend && npm ci && npm run build

test: ## Run all test suites
	cd backend && ./gradlew test
	cd agents && uv run pytest
	cd frontend && npm test

lint: ## Run all static-quality checks
	cd backend && ./gradlew spotlessCheck
	cd agents && uv run ruff check . && uv run ruff format --check . && uv run mypy
	cd frontend && npm run lint && npm run typecheck && npm run format:check

format: ## Auto-format all sub-projects
	cd backend && ./gradlew spotlessApply
	cd agents && uv run ruff format . && uv run ruff check --fix .
	cd frontend && npm run format

migrate: ## Database migrations — applied automatically by the backend on startup; no standalone command exists
	@echo "Flyway migrations V1-V11 (backend/src/main/resources/db/migration) run"
	@echo "automatically when the backend starts — there is no separate migrate command."
	@echo "Start the system with 'make up', then check status at"
	@echo "http://127.0.0.1:8080/actuator/flyway"
	@echo "To verify migrations against a real PostgreSQL + pgvector via Testcontainers:"
	@echo "cd backend && ./gradlew integrationTest"

seed: ## Register the initial curated source set against a running backend (scripts/seed-sources.sh)
	./scripts/seed-sources.sh
