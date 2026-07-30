from __future__ import annotations

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import __version__
from .config import settings
from .errors import AlignmentError
from .schemas import AlignRequest
from .service import alignment_service


app = FastAPI(title="Claudio Lyric Aligner", version=__version__)


@app.exception_handler(AlignmentError)
async def alignment_error_handler(_: Request, exc: AlignmentError) -> JSONResponse:
    return JSONResponse(
        status_code=exc.status_code,
        content={"error": {"message": str(exc), "type": exc.__class__.__name__}},
    )


@app.exception_handler(RequestValidationError)
async def request_validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    errors = []
    for item in exc.errors():
        errors.append(
            {
                "loc": item.get("loc", ()),
                "msg": item.get("msg", "invalid request"),
                "type": item.get("type", "value_error"),
            }
        )
    return JSONResponse(status_code=422, content={"error": {"message": "invalid request", "details": errors}})


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok", "version": __version__}


async def require_api_token(authorization: str | None = Header(default=None)) -> None:
    if not settings.api_token:
        return
    expected = f"Bearer {settings.api_token}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="unauthorized")


@app.post("/v1/align", dependencies=[Depends(require_api_token)])
async def align(request: AlignRequest):
    response = await alignment_service.align(request)
    return response.model_dump(mode="json", by_alias=True)
