"""Google Antigravity provider (local OpenAI-compatible bridge)."""
from providers import register_provider
from providers.base import ProviderProfile

antigravity = ProviderProfile(
    name="antigravity",
    aliases=("agy", "google-antigravity"),
    display_name="Google Antigravity",
    description="Google Antigravity subscription via local bridge",
    env_vars=(),
    base_url="http://127.0.0.1:8790/v1",
    default_max_tokens=65536,
)

register_provider(antigravity)
