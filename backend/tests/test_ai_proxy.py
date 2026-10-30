"""AI proxy tests (mocked Meta HTTP, no live calls, no real keys)."""

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import create_app
from app.ratelimit import limits
from app.routes import ai as ai_routes
from app.sessions import create_access

client = TestClient(create_app())
settings = get_settings()
TEST_KEY = "test-meta-key"


@pytest.fixture(autouse=True)
def _isolate():
    limits.reset()
    original = settings.meta_api_key
    settings.meta_api_key = TEST_KEY
    yield
    limits.reset()
    settings.meta_api_key = original


def _auth(sub="user-1"):
    token = create_access(sub, settings.jwt_secret, settings.jwt_access_minutes)
    return {"Authorization": f"Bearer {token}"}


def _use_meta(monkeypatch, handler):
    def factory():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), base_url="https://meta.test"
        )

    monkeypatch.setattr(ai_routes, "build_meta_client", factory)


def _chat_ok(request):
    assert request.url.path == "/chat/completions"
    assert request.headers["authorization"] == f"Bearer {TEST_KEY}"
    return httpx.Response(
        200,
        json={
            "choices": [
                {"message": {"content": "hi"}, "finish_reason": "stop"}
            ],
            "usage": {"prompt_tokens": 5, "completion_tokens": 7},
        },
    )


def test_chat_success(monkeypatch):
    _use_meta(monkeypatch, _chat_ok)
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hello"}]},
        headers=_auth(),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["choices"][0]["message"]["content"] == "hi"
    assert body["choices"][0]["finish_reason"] == "stop"
    assert body["usage"] == {"prompt_tokens": 5, "completion_tokens": 7}
    assert TEST_KEY not in response.text


def test_chat_requires_key(monkeypatch):
    settings.meta_api_key = ""

    def boom(request):
        raise AssertionError("meta must not be called without a key")

    _use_meta(monkeypatch, boom)
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hello"}]},
        headers=_auth(),
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "ai_unavailable"


def test_chat_requires_auth(monkeypatch):
    _use_meta(monkeypatch, _chat_ok)
    response = client.post(
        "/api/ai/chat", json={"messages": [{"role": "user", "content": "hi"}]}
    )
    assert response.status_code == 401


def test_chat_expired_session(monkeypatch):
    _use_meta(monkeypatch, _chat_ok)
    expired = create_access("user-1", settings.jwt_secret, -1)
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hi"}]},
        headers={"Authorization": f"Bearer {expired}"},
    )
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "expired_session"


def test_chat_accepts_fallback_model(monkeypatch):
    _use_meta(monkeypatch, _chat_ok)
    response = client.post(
        "/api/ai/chat",
        json={
            "model": "muse-spark-1.2",
            "messages": [{"role": "user", "content": "hi"}],
        },
        headers=_auth(),
    )
    assert response.status_code == 200


def test_chat_rejects_unknown_model(monkeypatch):
    _use_meta(monkeypatch, _chat_ok)
    response = client.post(
        "/api/ai/chat",
        json={"model": "evil-model", "messages": [{"role": "user", "content": "hi"}]},
        headers=_auth(),
    )
    assert response.status_code == 400


def test_chat_meta_busy_maps(monkeypatch):
    _use_meta(monkeypatch, lambda request: httpx.Response(429, json={}))
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hi"}]},
        headers=_auth(),
    )
    assert response.status_code == 429
    assert response.json()["error"]["code"] == "ai_busy"


def test_chat_retries_flapping_meta(monkeypatch):
    calls = []

    def flapping(request):
        calls.append(1)
        if len(calls) < 3:
            return httpx.Response(404, json={"error": {"code": "model_not_found"}})
        return _chat_ok(request)

    _use_meta(monkeypatch, flapping)
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hi"}]},
        headers=_auth(),
    )
    assert response.status_code == 200
    assert response.json()["choices"][0]["message"]["content"] == "hi"
    assert len(calls) == 3


def test_chat_gives_up_after_retries(monkeypatch):
    calls = []

    def missing(request):
        calls.append(1)
        return httpx.Response(404, json={"error": {"code": "model_not_found"}})

    _use_meta(monkeypatch, missing)
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hi"}]},
        headers=_auth(),
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "ai_unavailable"
    assert len(calls) == 3


def test_chat_meta_error_body_never_forwarded(monkeypatch):
    _use_meta(
        monkeypatch,
        lambda request: httpx.Response(500, json={"secret": "LEAK-MARKER"}),
    )
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hi"}]},
        headers=_auth(),
    )
    assert response.status_code == 502
    assert "LEAK-MARKER" not in response.text


def test_chat_timeout_maps(monkeypatch):
    def timeout(request):
        raise httpx.TimeoutException("slow")

    _use_meta(monkeypatch, timeout)
    response = client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hi"}]},
        headers=_auth(),
    )
    assert response.status_code == 504


def test_chat_rate_limited(monkeypatch):
    _use_meta(monkeypatch, _chat_ok)
    headers = _auth()
    body = {"messages": [{"role": "user", "content": "hi"}]}
    for _ in range(settings.chat_per_minute):
        assert client.post("/api/ai/chat", json=body, headers=headers).status_code == 200
    response = client.post("/api/ai/chat", json=body, headers=headers)
    assert response.status_code == 429
    assert response.json()["error"]["code"] == "rate_limited"


BILL_JSON = (
    '{"provider":"TNB","amount":120.5,"currency":"MYR","due_date":"2026-11-15",'
    '"confidence":0.9,"missing_fields":[],"summary":"Electricity bill."}'
)


def _bill_ok(request):
    import json as jsonlib

    payload = jsonlib.loads(request.content.decode())
    assert "response_format" not in payload  # Meta 400s schemaless json_schema
    content = payload["messages"][0]["content"]
    assert content[0]["type"] == "text"
    assert content[1]["type"] == "image_url"
    assert content[1]["image_url"]["url"].startswith("data:image/jpeg;base64,")
    return httpx.Response(
        200,
        json={"choices": [{"message": {"content": BILL_JSON}}], "usage": {}},
    )


def test_bill_extract_success(monkeypatch):
    _use_meta(monkeypatch, _bill_ok)
    response = client.post(
        "/api/ai/bill-extract",
        json={"image_base64": "QUJD", "ocr_hint": "TNB"},
        headers=_auth(),
    )
    assert response.status_code == 200
    assert response.json()["provider"] == "TNB"
    assert TEST_KEY not in response.text


def test_bill_extract_retries_bad_json(monkeypatch):
    calls = []

    def flaky(request):
        calls.append(1)
        content = "not json" if len(calls) == 1 else BILL_JSON
        return httpx.Response(200, json={"choices": [{"message": {"content": content}}]})

    _use_meta(monkeypatch, flaky)
    response = client.post(
        "/api/ai/bill-extract", json={"image_base64": "QUJD"}, headers=_auth()
    )
    assert response.status_code == 200
    assert response.json()["provider"] == "TNB"
    assert len(calls) == 2


def test_bill_extract_unclear_after_retries(monkeypatch):
    _use_meta(
        monkeypatch,
        lambda request: httpx.Response(
            200, json={"choices": [{"message": {"content": "nope"}}]}
        ),
    )
    response = client.post(
        "/api/ai/bill-extract", json={"image_base64": "QUJD"}, headers=_auth()
    )
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "ai_unclear"


def test_bill_extract_rejects_oversize(monkeypatch):
    _use_meta(monkeypatch, _bill_ok)
    response = client.post(
        "/api/ai/bill-extract",
        json={"image_base64": "x" * (settings.max_image_b64_chars + 1)},
        headers=_auth(),
    )
    assert response.status_code == 413
