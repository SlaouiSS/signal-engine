-- Signal Engine schema — V12: AI capability suspension.
--
-- A capability whose provider configuration was refused (bad credentials, a retired
-- model: HTTP 401/402/403/404/410 reported by the Python service as
-- providerFailureScope = configuration) fails every request the same way until a
-- person fixes the configuration. Retrying it for each item, or on each restart,
-- only burns calls and floods the activity history, so the backend suspends the
-- capability instead (docs/adr/0018-pipeline-reliability.md; failure taxonomy in
-- docs/03-technical-spec.md Section 13.1-13.2).
--
-- One row per suspended capability; the row is deleted when a call succeeds. While
-- suspended_until is in the future the capability is not called at all. Once it has
-- passed, exactly one caller is allowed to probe (the row is claimed by moving
-- suspended_until forward); a failed probe suspends again for a longer cooldown
-- (exponential, capped), a successful one clears the suspension. Items are never
-- touched by a suspension, so none is lost.
--
-- Manual recovery after fixing the configuration, without waiting for the cooldown:
--   DELETE FROM ai_capability_suspension WHERE capability = '<capability>';

CREATE TABLE ai_capability_suspension (
    -- The capability name as sent to the Python service, e.g. "relevance", "embed".
    capability            TEXT PRIMARY KEY,

    -- When the current uninterrupted run of suspension began.
    suspended_since       TIMESTAMPTZ NOT NULL,

    -- The capability is not called before this instant.
    suspended_until       TIMESTAMPTZ NOT NULL,

    -- How many consecutive times the provider configuration was refused; drives the
    -- exponential cooldown.
    consecutive_failures  INTEGER NOT NULL CHECK (consecutive_failures >= 1),

    -- The HTTP status the provider answered with, when known; never a secret.
    provider_status       INTEGER,

    -- The failure message reported by the Python service; never a secret.
    reason                TEXT NOT NULL,

    updated_at            TIMESTAMPTZ NOT NULL
);


-- Evidence that a capability works: when a call of it last succeeded. A permanent rejection that
-- looks specific to one request (HTTP 400, 413, 422...) is only that request's own fault if the
-- capability is known to work; otherwise it may be a parameter the provider does not accept for any
-- request, and the capability is suspended instead of failing items one by one
-- (docs/adr/0018-pipeline-reliability.md).
CREATE TABLE ai_capability_health (
    capability       TEXT PRIMARY KEY,
    last_success_at  TIMESTAMPTZ NOT NULL
);
