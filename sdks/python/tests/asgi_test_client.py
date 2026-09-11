"""Stable synchronous facade over httpx's async in-process ASGI transport."""

from __future__ import annotations

import asyncio

import httpx


class ASGITestClient:
    def __init__(self, app, runtime=None):
        self.app = app
        self.runtime = runtime

    def __enter__(self):
        return self

    def __exit__(self, _exc_type, _exc_value, _traceback):
        return False

    def get(self, path: str, **kwargs):
        return self.request("GET", path, **kwargs)

    def post(self, path: str, **kwargs):
        return self.request("POST", path, **kwargs)

    def request(self, method: str, path: str, **kwargs):
        async def exchange():
            transport = httpx.ASGITransport(app=self.app)
            async with httpx.AsyncClient(
                transport=transport, base_url="http://testserver"
            ) as client:
                response = await client.request(method, path, **kwargs)
                if self.runtime is not None:
                    while True:
                        tasks = tuple(self.runtime._runtime_work._callback_tasks)
                        if not tasks:
                            break
                        await asyncio.gather(*tasks, return_exceptions=True)
                return response

        return asyncio.run(exchange())
