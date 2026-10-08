"""Cancellable callback I/O; response bodies are never needed for delivery."""
import asyncio
import logging

import httpx

logger = logging.getLogger(__name__)


async def post_callback(url: str, body: bytes, headers: dict[str, str], timeout_seconds: float) -> int:
    client = httpx.AsyncClient(timeout=timeout_seconds, follow_redirects=False)
    failure = None
    try:
        async with asyncio.timeout(timeout_seconds):
            request = client.build_request("POST", url, content=body, headers=headers)
            for redirects in range(31):
                response = await client.send(request, stream=True, follow_redirects=False)
                try:
                    status = response.status_code
                    next_request = response.next_request
                finally:
                    await response.aclose()
                if next_request is None:
                    return status
                if redirects == 30:
                    raise httpx.TooManyRedirects("Callback exceeded 30 redirects", request=next_request)
                request = next_request
    except BaseException as error:
        failure = error
        raise
    finally:
        try:
            async with asyncio.timeout(min(timeout_seconds, 0.1)):
                await client.aclose()
        except BaseException:
            if failure is None:
                raise
            logger.warning("Callback transport cleanup failed", exc_info=True)
