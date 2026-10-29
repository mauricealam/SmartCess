"""Google ID-token verification. Only Google's verdict is trusted."""

from google.auth.transport import requests as google_requests
from google.oauth2 import id_token as google_id_token


def verify_google_id_token(token: str, server_client_id: str) -> str:
    """Returns the Google subject (stable user id) or raises ValueError."""
    if not server_client_id:
        raise ValueError("server misconfigured")
    try:
        info = google_id_token.verify_oauth2_token(
            token, google_requests.Request(), server_client_id
        )
    except Exception as e:
        raise ValueError("invalid Google token") from e
    sub = info.get("sub")
    if not sub:
        raise ValueError("invalid Google token")
    return sub
