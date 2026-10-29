"""Request dependencies: session auth for HTTP routes."""

import jwt
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .config import get_settings
from .errors import session_expired, sign_in_required
from .sessions import decode_access

bearer = HTTPBearer(auto_error=False)


def current_sub(
    creds: HTTPAuthorizationCredentials | None = Depends(bearer),
) -> str:
    """Returns the Google subject for a valid access token (401 otherwise)."""
    if creds is None or not creds.credentials:
        raise sign_in_required()
    try:
        return decode_access(creds.credentials, get_settings().jwt_secret)
    except jwt.ExpiredSignatureError:
        raise session_expired()
    except jwt.PyJWTError:
        raise sign_in_required()
