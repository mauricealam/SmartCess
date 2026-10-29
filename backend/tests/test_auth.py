"""Auth route tests (mocked Google verification, no network)."""

from fastapi.testclient import TestClient

from app.config import get_settings
from app.main import create_app
from app.routes import auth as auth_routes
from app.sessions import create_access, create_refresh, decode_access, decode_refresh

client = TestClient(create_app())
settings = get_settings()


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_google_exchange_success(monkeypatch):
    monkeypatch.setattr(
        auth_routes, "verify_google_id_token", lambda token, cid: "google-sub-1"
    )
    response = client.post("/api/auth/google", json={"id_token": "fake-token"})
    assert response.status_code == 200
    body = response.json()
    assert body["expires_in"] == settings.jwt_access_minutes * 60
    assert decode_access(body["access_token"], settings.jwt_secret) == "google-sub-1"
    assert decode_refresh(body["refresh_token"], settings.jwt_secret) == "google-sub-1"


def test_google_exchange_rejected(monkeypatch):
    def boom(token, cid):
        raise ValueError("bad")

    monkeypatch.setattr(auth_routes, "verify_google_id_token", boom)
    response = client.post("/api/auth/google", json={"id_token": "fake-token"})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "invalid_token"


def test_refresh_issues_new_pair():
    old = create_refresh("google-sub-1", settings.jwt_secret, settings.jwt_refresh_days)
    response = client.post("/api/auth/refresh", json={"refresh_token": old})
    assert response.status_code == 200
    body = response.json()
    assert decode_access(body["access_token"], settings.jwt_secret) == "google-sub-1"


def test_refresh_with_access_token_rejected():
    access = create_access("x", settings.jwt_secret, settings.jwt_access_minutes)
    response = client.post("/api/auth/refresh", json={"refresh_token": access})
    assert response.status_code == 401


def test_refresh_expired_rejected():
    expired = create_refresh("x", settings.jwt_secret, -1)
    response = client.post("/api/auth/refresh", json={"refresh_token": expired})
    assert response.status_code == 401
    assert response.json()["error"]["code"] == "expired_session"


def test_signout_ok():
    assert client.post("/api/auth/signout").json() == {"ok": True}
