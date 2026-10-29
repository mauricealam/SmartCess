"""App errors: plain-language messages, never Meta internals or stack traces."""

from fastapi import Request
from fastapi.responses import JSONResponse


class AppError(Exception):
    def __init__(self, code: str, message: str, status: int):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status


async def app_error_handler(request: Request, exc: AppError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status,
        content={"error": {"code": exc.code, "message": exc.message}},
    )


def sign_in_required() -> AppError:
    return AppError("invalid_token", "Please sign in.", 401)


def session_expired() -> AppError:
    return AppError("expired_session", "Session expired. Please sign in again.", 401)


def rate_limited() -> AppError:
    return AppError("rate_limited", "Too many requests. Please wait a moment.", 429)


def ai_busy() -> AppError:
    return AppError(
        "ai_busy", "The AI service is busy. Please try again in a moment.", 429
    )


def ai_unavailable() -> AppError:
    return AppError(
        "ai_unavailable",
        "The AI service is unavailable. Please try again later.",
        502,
    )


def ai_timeout() -> AppError:
    return AppError(
        "ai_unavailable",
        "The AI service timed out. Please try again.",
        504,
    )


def ai_unclear() -> AppError:
    return AppError(
        "ai_unclear", "The AI answer was unclear. Please try again.", 502
    )


def map_meta_http(status: int) -> AppError:
    """Maps a Meta HTTP status to a safe app error (body never forwarded)."""
    if status == 429:
        return ai_busy()
    if status == 400:
        return ai_unclear()
    return ai_unavailable()
