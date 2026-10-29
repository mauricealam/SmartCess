"""Authentication: Google ID token in, SmartCess session pair out."""

import jwt
from fastapi import APIRouter
from pydantic import BaseModel

from ..config import get_settings
from ..errors import AppError, session_expired
from ..google_verify import verify_google_id_token
from ..sessions import create_access, create_refresh, decode_refresh

router = APIRouter()


class GoogleExchange(BaseModel):
    id_token: str


class RefreshRequest(BaseModel):
    refresh_token: str


def _pair(sub: str) -> dict:
    settings = get_settings()
    return {
        "access_token": create_access(
            sub, settings.jwt_secret, settings.jwt_access_minutes
        ),
        "refresh_token": create_refresh(
            sub, settings.jwt_secret, settings.jwt_refresh_days
        ),
        "expires_in": settings.jwt_access_minutes * 60,
    }


@router.post("/google")
def exchange(body: GoogleExchange) -> dict:
    try:
        sub = verify_google_id_token(
            body.id_token, get_settings().google_server_client_id
        )
    except ValueError:
        raise AppError(
            "invalid_token", "Google sign-in failed. Please try again.", 401
        )
    return _pair(sub)


@router.post("/refresh")
def refresh(body: RefreshRequest) -> dict:
    try:
        sub = decode_refresh(body.refresh_token, get_settings().jwt_secret)
    except jwt.ExpiredSignatureError:
        raise session_expired()
    except jwt.PyJWTError:
        raise AppError("invalid_token", "Please sign in.", 401)
    return _pair(sub)


@router.post("/signout")
def signout() -> dict:
    # Stateless v1: no server-side revocation; the app wipes its tokens.
    return {"ok": True}
