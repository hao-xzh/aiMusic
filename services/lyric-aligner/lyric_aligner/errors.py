from __future__ import annotations


class AlignmentError(RuntimeError):
    status_code = 422

    def __init__(self, message: str, *, status_code: int | None = None):
        super().__init__(message)
        if status_code is not None:
            self.status_code = status_code


class DownloadError(AlignmentError):
    status_code = 400


class EngineUnavailable(AlignmentError):
    status_code = 503


class AlignmentRejected(AlignmentError):
    status_code = 422
