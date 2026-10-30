"""Push-to-talk upload tests (mocked Meta HTTP, no live calls, no real keys)."""

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import create_app
from app.ratelimit import limits
from app.routes import voice as voice_routes
from app.sessions import create_access

client = TestClient(create_app())
settings = get_settings()
TEST_KEY = "test-meta-key"
MARKER = "TURNIP-TRUCK-TRANSCRIPT"


def _wav(n=1600):
    """Minimal valid RIFF/WAVE container (mono 16-bit header + PCM)."""
    pcm = b"\x00\x01" * (n // 2)
    return (
        b"RIFF"
        + (36 + len(pcm)).to_bytes(4, "little")
        + b"WAVEfmt "
        + (16).to_bytes(4, "little")
        + (1).to_bytes(2, "little")  # PCM
        + (1).to_bytes(2, "little")  # mono
        + (16000).to_bytes(4, "little")
        + (32000).to_bytes(4, "little")
        + (2).to_bytes(2, "little")
        + (16).to_bytes(2, "little")
        + b"data"
        + len(pcm).to_bytes(4, "little")
        + pcm
    )


@pytest.fixture(autouse=True)
def _isolate():
    limits.reset()
    voice_routes.active.clear()
    original = settings.meta_api_key
    settings.meta_api_key = TEST_KEY
    yield
    limits.reset()
    voice_routes.active.clear()
    settings.meta_api_key = original


def _auth(sub="user-1"):
    token = create_access(sub, settings.jwt_secret, settings.jwt_access_minutes)
    return {"Authorization": f"Bearer {token}"}


def _use_meta(monkeypatch, handler):
    def factory(timeout_s=None):
        return httpx.AsyncClient(
            transport=httpx.MockTransport(handler), base_url="https://meta.test"
        )

    monkeypatch.setattr(voice_routes, "build_meta_client", factory)


def _post(wav, headers):
    return client.post(
        "/api/voice/transcribe",
        files={"audio": ("talk.wav", wav, "audio/wav")},
        headers=headers,
    )


def test_transcribe_success_and_meta_contract(monkeypatch):
    seen = {}

    def meta_ok(request):
        import json as jsonlib

        seen["path"] = request.url.path
        seen["auth"] = request.headers["authorization"]
        seen["content_type"] = request.headers["content-type"]
        body = request.content.decode("latin1")
        assert "muse-voice-transcribe-1.0" in body
        assert "PUSH_TO_TALK" in body
        assert "WAV" in body
        assert "talk.wav" in body
        return httpx.Response(
            200,
            json={
                "sessionId": "s1",
                "transcript": MARKER,
                "audioDurationMs": 1200,
                "turns": [{"transcript": MARKER}],
            },
        )

    _use_meta(monkeypatch, meta_ok)
    response = _post(_wav(), _auth())
    assert response.status_code == 200
    assert response.json() == {
        "transcript": MARKER,
        "audio_duration_ms": 1200,
        "turns": 1,
    }
    assert seen["path"] == "/asr/transcribe"
    assert seen["auth"] == f"Bearer {TEST_KEY}"
    assert "multipart/form-data" in seen["content_type"]
    assert TEST_KEY not in response.text


def test_transcribe_requires_auth(monkeypatch):
    _use_meta(monkeypatch, lambda request: httpx.Response(200, json={}))
    response = _post(_wav(), {})
    assert response.status_code == 401


def test_transcribe_expired_session(monkeypatch):
    _use_meta(monkeypatch, lambda request: httpx.Response(200, json={}))
    expired = create_access("user-1", settings.jwt_secret, -1)
    response = _post(_wav(), {"Authorization": f"Bearer {expired}"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "expired_session"


def test_transcribe_rejects_non_wav(monkeypatch):
    def boom(request):
        raise AssertionError("meta must not see non-audio")

    _use_meta(monkeypatch, boom)
    response = _post(b"not audio at all", _auth())
    assert response.status_code == 400


def test_transcribe_rejects_oversize(monkeypatch):
    def boom(request):
        raise AssertionError("meta must not see oversize uploads")

    _use_meta(monkeypatch, boom)
    big = b"RIFF" + b"\x00" * settings.max_audio_bytes + b"WAVE"
    response = _post(big, _auth())
    assert response.status_code == 413


def test_transcribe_blank_transcript_is_unclear(monkeypatch):
    _use_meta(
        monkeypatch,
        lambda request: httpx.Response(
            200, json={"transcript": "  ", "audioDurationMs": 900, "turns": []}
        ),
    )
    response = _post(_wav(), _auth())
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "ai_unclear"


def test_transcribe_meta_busy(monkeypatch):
    _use_meta(monkeypatch, lambda request: httpx.Response(429, json={}))
    response = _post(_wav(), _auth())
    assert response.status_code == 429


def test_transcribe_retries_flapping_meta(monkeypatch):
    calls = []

    def flaky(request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(404, json={"error": {"code": "model_not_found"}})
        return httpx.Response(200, json={"transcript": MARKER})

    _use_meta(monkeypatch, flaky)
    response = _post(_wav(), _auth())
    assert response.status_code == 200
    assert response.json()["transcript"] == MARKER
    assert len(calls) == 2


def test_transcribe_meta_timeout(monkeypatch):
    def timeout(request):
        raise httpx.TimeoutException("slow")

    _use_meta(monkeypatch, timeout)
    response = _post(_wav(), _auth())
    assert response.status_code == 504


def test_transcribe_concurrent_is_busy(monkeypatch):
    def boom(request):
        raise AssertionError("second flight must not reach meta")

    _use_meta(monkeypatch, boom)
    voice_routes.active.add("user-1")
    response = _post(_wav(), _auth())
    assert response.status_code == 429


def test_transcribe_logs_counts_not_content(monkeypatch, caplog):
    import logging

    _use_meta(
        monkeypatch,
        lambda request: httpx.Response(
            200,
            json={
                "transcript": MARKER,
                "audioDurationMs": 1200,
                "turns": [{"transcript": MARKER}],
            },
        ),
    )
    with caplog.at_level(logging.INFO, logger="smartcess.voice"):
        response = _post(_wav(), _auth())
    assert response.status_code == 200
    assert MARKER not in caplog.text
    assert "stt file" in caplog.text
