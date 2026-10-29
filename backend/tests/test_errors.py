"""Error mapping tests (Meta statuses -> safe app errors)."""

from app.errors import AppError, map_meta_http


def test_rate_limit_maps_to_busy():
    err = map_meta_http(429)
    assert isinstance(err, AppError)
    assert err.status == 429
    assert err.code == "ai_busy"


def test_auth_failures_map_to_unavailable():
    for status in (400, 401, 403, 500, 503):
        err = map_meta_http(status)
        assert err.status == 502
        assert "unavailable" in err.message or "unclear" in err.message


def test_body_never_forwarded():
    err = map_meta_http(500)
    assert "sk-" not in err.message and "LLM" not in err.message
