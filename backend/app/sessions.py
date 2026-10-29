"""Stateless sessions (free-tier v1: no database).

Access tokens live 15 minutes; refresh tokens 7 days. Both are signed JWTs,
so any instance can validate them with the shared secret. Trade-off: the
server cannot revoke a token early — sign-out wipes tokens client-side and
short lifetimes bound exposure. A stored-refresh-token upgrade is the path
when persistence exists.
"""

import time
import uuid

import jwt

ACCESS_AUD = "smartcess-access"
REFRESH_AUD = "smartcess-refresh"


def create_access(sub: str, secret: str, minutes: int) -> str:
    now = int(time.time())
    return jwt.encode(
        {"sub": sub, "aud": ACCESS_AUD, "iat": now, "exp": now + minutes * 60},
        secret,
        algorithm="HS256",
    )


def create_refresh(sub: str, secret: str, days: int) -> str:
    now = int(time.time())
    return jwt.encode(
        {
            "sub": sub,
            "aud": REFRESH_AUD,
            "jti": uuid.uuid4().hex,
            "iat": now,
            "exp": now + days * 86400,
        },
        secret,
        algorithm="HS256",
    )


def decode_access(token: str, secret: str) -> str:
    """Returns the subject or raises jwt.PyJWTError."""
    payload = jwt.decode(
        token, secret, algorithms=["HS256"], audience=ACCESS_AUD,
        options={"require": ["sub", "exp"]},
    )
    return payload["sub"]


def decode_refresh(token: str, secret: str) -> str:
    """Returns the subject or raises jwt.PyJWTError."""
    payload = jwt.decode(
        token, secret, algorithms=["HS256"], audience=REFRESH_AUD,
        options={"require": ["sub", "exp"]},
    )
    return payload["sub"]
