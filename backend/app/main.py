"""SmartCess backend: Google auth, Meta AI proxy, voice relay."""

import logging
import time
import uuid

from fastapi import FastAPI, Request

from .config import get_settings
from .errors import AppError, app_error_handler
from .routes import ai, auth, voice

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
)


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="SmartCess backend", version=settings.app_version)

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("x-request-id", uuid.uuid4().hex[:12])
        started = time.monotonic()
        response = await call_next(request)
        response.headers["X-Request-Id"] = request_id
        logging.getLogger("smartcess.http").info(
            "%s %s -> %s %.0fms rid=%s",
            request.method,
            request.url.path,
            response.status_code,
            (time.monotonic() - started) * 1000,
            request_id,
        )
        return response

    app.add_exception_handler(AppError, app_error_handler)

    @app.get("/health")
    def health() -> dict:
        return {"status": "ok", "version": settings.app_version}

    app.include_router(auth.router, prefix="/api/auth")
    app.include_router(ai.router, prefix="/api/ai")
    app.include_router(voice.router, prefix="/api/voice")
    return app


app = create_app()
