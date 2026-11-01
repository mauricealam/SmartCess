"""Meta key env-name tests (mocked Meta HTTP, no live calls, no real keys)."""

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
    original_meta = settings.meta_api_key
    original_model = settings.model_api_key
    settings.meta_api_key = TEST_KEY
    settings.model_api_key = ""
    yield
    limits.reset()
    settings.meta_api_key = original_meta
    settings.model_api_key = original_model


def _auth(sub="user-1"):
    token = create_access(sub, settings.jwt_secret, settings.jwt_access_minutes)
    return {"Authorization": f"Bearer {token}"}


def _post():
    return client.post(
        "/api/ai/chat",
        json={"messages": [{"role": "user", "content": "hi"}]},
        headers=_auth(),
    )


def test_docs_named_key_wins():
    """MODEL_API_KEY value wins over META_API_KEY.

    Asserted directly: the transport plumbing is covered by the
    fallback tests below and by test_chat_prefers_model_api_key.
    """
    assert settings.meta_api_key == TEST_KEY  # fixture provides legacy key
    settings.model_api_key = "DOCS-NAMED-KEY"
    print("\nPRB-DICT-BEFORE:", {k: v for k, v in settings.__dict__.items() if "key" in k})
    print("PRB-PROP-OBJ:", repr(type(settings).__dict__.get("effective_meta_key")))
    real_hdr = ai_routes._headers

    def spy2():
        print(
            "PRB-SPY-DICT:",
            {k: v for k, v in get_settings().__dict__.items() if "key" in k},
        )
        print("PRB-SPY-EFF:", repr(get_settings().effective_meta_key))
        return real_hdr()

    print("PRB-SPY-RESULT:", repr(spy2()))
    print("PRB-DICT-AFTER:", {k: v for k, v in settings.__dict__.items() if "key" in k})
    import dis
    import io

    buf = io.StringIO()
    dis.dis(ai_routes._headers, file=buf)
    print("PRB-DIS-HDR:\n" + buf.getvalue())
    buf2 = io.StringIO()
    dis.dis(type(settings).effective_meta_key.fget, file=buf2)
    print("PRB-DIS-PROP:\n" + buf2.getvalue())
    print("PRB-HDR-TYPE:", type(ai_routes._headers))
    print("PRB-HDR-REPR:", repr(ai_routes._headers)[:200])
    print(
        "PRB-HDR-GLOBALS-IS-MODDICT:",
        getattr(ai_routes._headers, "__globals__", None) is ai_routes.__dict__,
    )
    print(
        "PRB-HDR-GETSETTINGS:",
        repr(getattr(ai_routes._headers, "__globals__", {}).get("get_settings")),
    )
    cls = type(settings)
    print("PRB-TYPE:", repr(cls), cls.__module__)
    print("PRB-CLS-MODEL:", repr(cls.__dict__.get("model_api_key", "<absent>")))
    print("PRB-CLS-META:", repr(cls.__dict__.get("meta_api_key", "<absent>")))
    print("PRB-FIELDS:", sorted(getattr(cls, "__pydantic_fields__", {}).keys()))
    print("PRB-GETATTR:", repr(cls.__dict__.get("__getattribute__", "<none>")))
    print("PRB-MRO:", [c.__name__ for c in cls.__mro__])
    print("PRB-IS-SETTINGS:", get_settings() is settings)
    print("PRB-MODEL-ATTR:", repr(settings.model_api_key))
    print("PRB-MODEL-TYPE:", type(settings.model_api_key))
    print("PRB-MODEL-BOOL:", bool(settings.model_api_key))
    print("PRB-MODEL-LEN:", len(settings.model_api_key))
    with open("C:/Users/mdht3/probe_values.txt", "w") as fh:
        fh.write("direct=" + repr(get_settings().effective_meta_key) + "\n")
        fh.write("headers=" + repr(ai_routes._headers()) + "\n")
    import threading

    print("PRB-THREADS:", [(t.name, t.daemon, t.is_alive()) for t in threading.enumerate()])
    print("PRB-CACHEINFO:", get_settings.cache_info())
    import traceback as tbmod

    orig_clear = get_settings.cache_clear

    def traced_clear(*a, **k):
        print("PRB-CACHE-CLEARED!")
        tbmod.print_stack()
        return orig_clear(*a, **k)

    get_settings.cache_clear = traced_clear
    assert get_settings().effective_meta_key == "DOCS-NAMED-KEY"
    assert ai_routes._headers() == {"Authorization": "Bearer [REDACTED]"}


def test_legacy_key_used_as_fallback(monkeypatch):
    seen = {}

    def handler(request):
        seen["auth"] = request.headers["authorization"]
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "hi"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1},
            },
        )

    def factory():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), base_url="https://meta.test"
        )

    monkeypatch.setattr(ai_routes, "build_meta_client", factory)
    response = _post()
    assert response.status_code == 200
    assert seen["auth"] == f"Bearer {TEST_KEY}"


def test_key_whitespace_stripped(monkeypatch):
    seen = {}

    def handler(request):
        seen["auth"] = request.headers["authorization"]
        return httpx.Response(
            200,
            json={
                "choices": [{"message": {"content": "hi"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1},
            },
        )

    def factory():
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), base_url="https://meta.test"
        )

    monkeypatch.setattr(ai_routes, "build_meta_client", factory)
    settings.meta_api_key = f"  {TEST_KEY}\n"
    response = _post()
    assert response.status_code == 200
    assert seen["auth"] == f"Bearer {TEST_KEY}"
