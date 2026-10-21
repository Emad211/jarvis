"""AgentRouter provider profile (user plugin).

AgentRouter's WAF only accepts requests whose User-Agent looks like an
official coding-agent client (claude-cli / codex / ...). Generic SDK
User-Agents get HTTP 401 "unauthorized client detected". This profile
sends the Claude Code client fingerprint via default_headers so the
gateway accepts Hermes requests.
"""

from providers import register_provider
from providers.base import ProviderProfile

agentrouter = ProviderProfile(
    name="agentrouter",
    aliases=("agent-router",),
    display_name="AgentRouter",
    description="AgentRouter — OpenAI-compatible gateway (Claude/DeepSeek/GLM)",
    signup_url="https://agentrouter.org/console",
    env_vars=("AGENTROUTER_API_KEY",),
    base_url="https://agentrouter.org/v1",
    auth_type="api_key",
    fallback_models=("deepseek-v4-flash",),
    default_aux_model="deepseek-v4-flash",
    default_headers={
        "User-Agent": "claude-cli/2.1.158 (external, sdk-cli)",
        "x-app": "cli",
    },
)

register_provider(agentrouter)
