"""Meta Model API HTTP client factory (seam for tests)."""

import httpx

from .config import get_settings


def build_meta_client(timeout_s: float | None = None) -> httpx.AsyncClient:
    settings = get_settings()
    return httpx.AsyncClient(
        base_url=settings.meta_base_url,
        timeout=httpx.Timeout(
            timeout_s if timeout_s is not None else settings.chat_timeout_s
        ),
    )
