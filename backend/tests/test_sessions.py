"""Session issue/validate/expiry tests (no network)."""

import jwt
import pytest

from app.sessions import (
    ACCESS_AUD,
    REFRESH_AUD,
    create_access,
    create_refresh,
    decode_access,
    decode_refresh,
)

SECRET = "test-secret"


def test_access_round_trip():
    assert decode_access(create_access("sub-1", SECRET, 15), SECRET) == "sub-1"


def test_refresh_round_trip():
    assert decode_refresh(create_refresh("sub-1", SECRET, 7), SECRET) == "sub-1"


def test_expired_access_rejected():
    token = create_access("sub-1", SECRET, -1)
    with pytest.raises(jwt.ExpiredSignatureError):
        decode_access(token, SECRET)


def test_wrong_secret_rejected():
    token = create_access("sub-1", SECRET, 15)
    with pytest.raises(jwt.PyJWTError):
        decode_access(token, "other-secret")


def test_tampered_token_rejected():
    token = create_access("sub-1", SECRET, 15)
    with pytest.raises(jwt.PyJWTError):
        decode_access(token[:-2] + ("aa" if not token.endswith("aa") else "bb"), SECRET)


def test_cross_audience_rejected():
    access = create_access("sub-1", SECRET, 15)
    refresh = create_refresh("sub-1", SECRET, 7)
    with pytest.raises(jwt.PyJWTError):
        decode_refresh(access, SECRET)
    with pytest.raises(jwt.PyJWTError):
        decode_access(refresh, SECRET)


def test_audiences_differ():
    assert ACCESS_AUD != REFRESH_AUD
