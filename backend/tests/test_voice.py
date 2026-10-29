"""Voice relay tests (fake Meta socket, no live calls, no real keys)."""

import asyncio
import json

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from app.config import get_settings
from app.main import create_app
from app.routes import voice as voice_routes
from app.sessions import create_access

client = TestClient(create_app())
settings = get_settings()
TEST_KEY = "test-meta-key"


class FakeClosed(Exception):
    pass


class FakeMeta:
    """Scripted Meta socket. Script items: str frames, bytes, 'HANG', or FakeClosed."""

    def __init__(self, script):
        self.script = list(script)
        self.sent = []
        self.closed = False
        self.url = None

    async def send(self, data):
        self.sent.append(data)

    async def recv(self):
        # Behave like a network socket: scripted instant replies would win
        # every relay race and starve the TestClient portal round-trip.
        await asyncio.sleep(0.01)
        if not self.script:
            raise FakeClosed()
        item = self.script.pop(0)
        if item == "HANG":
            await asyncio.sleep(30)
            raise FakeClosed()
        if isinstance(item, Exception):
            raise item
        return item

    async def close(self):
        self.closed = True


@pytest.fixture()
def meta_key():
    original = settings.meta_api_key
    settings.meta_api_key = TEST_KEY
    yield
    settings.meta_api_key = original


@pytest.fixture()
def clean_active():
    voice_routes.active.clear()
    yield
    voice_routes.active.clear()


def _token(sub="user-9"):
    return create_access(sub, settings.jwt_secret, settings.jwt_access_minutes)


def test_relay_happy_path(meta_key, clean_active, monkeypatch):
    fake = FakeMeta(
        [
            '{"type":"transcript","transcript":"hi","final":false}',
            '{"type":"transcript","transcript":"hi there","final":true}',
            FakeClosed(),
        ]
    )

    async def connect(url):
        fake.url = url
        return fake

    monkeypatch.setattr(voice_routes, "connect_meta", connect)
    with client.websocket_connect(f"/api/voice/realtime?token={_token()}") as ws:
        ws.send_bytes(b"\x00\x01pcm")
        first = ws.receive_text()
        assert "hi" in first
        assert TEST_KEY not in first
        ws.send_text('{"type":"endStream"}')
        second = ws.receive_text()
        assert "hi there" in second
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_text()
        assert exc.value.code == 1000
    assert "sessionId=relay-" in fake.url
    handshake = json.loads(fake.sent[0])
    assert handshake["authorization"]["accessToken"] == f"Bearer {TEST_KEY}"
    assert handshake["audioEncoding"] == "PCM_24KHZ"
    assert fake.sent[1] == b"\x00\x01pcm"
    assert json.loads(fake.sent[2])["type"] == "endStream"
    assert fake.closed


def test_rejects_bad_token(meta_key, clean_active, monkeypatch):
    connected = []

    async def connect(url):
        connected.append(url)
        raise AssertionError("must not dial meta without auth")

    monkeypatch.setattr(voice_routes, "connect_meta", connect)
    with client.websocket_connect("/api/voice/realtime?token=bogus") as ws:
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_text()
        assert exc.value.code == voice_routes.WS_AUTH_CLOSE
    assert not connected


def test_rejects_when_busy(meta_key, clean_active, monkeypatch):
    async def connect(url):
        raise AssertionError("must not dial meta when busy")

    monkeypatch.setattr(voice_routes, "connect_meta", connect)
    voice_routes.active.add("user-9")
    with client.websocket_connect(f"/api/voice/realtime?token={_token()}") as ws:
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_text()
        assert exc.value.code == voice_routes.WS_BUSY_CLOSE


def test_missing_key_closes(meta_key, clean_active, monkeypatch):
    settings.meta_api_key = ""

    async def connect(url):
        raise AssertionError("must not dial meta without a key")

    monkeypatch.setattr(voice_routes, "connect_meta", connect)
    with client.websocket_connect(f"/api/voice/realtime?token={_token()}") as ws:
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_text()
        assert exc.value.code == voice_routes.WS_UNAVAILABLE_CLOSE


def test_idle_timeout_closes(meta_key, clean_active, monkeypatch):
    fake = FakeMeta(["HANG"])

    async def connect(url):
        return fake

    monkeypatch.setattr(voice_routes, "connect_meta", connect)
    original_idle = settings.ws_idle_s
    settings.ws_idle_s = 0.2
    try:
        with client.websocket_connect(f"/api/voice/realtime?token={_token()}") as ws:
            with pytest.raises(WebSocketDisconnect) as exc:
                ws.receive_text()
            assert exc.value.code == 1000
    finally:
        settings.ws_idle_s = original_idle
    assert fake.closed
