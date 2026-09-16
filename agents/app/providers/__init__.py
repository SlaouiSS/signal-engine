"""LLM provider abstraction and adapters (docs/03-technical-spec.md Section 9.2).

Capability code depends on the :class:`~app.providers.base.LlmProvider` Protocol
only; adding a provider is a new adapter here with no change to any capability.
"""
