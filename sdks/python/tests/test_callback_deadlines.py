"""Real sockets prove callback deadlines and physical transport release."""
import asyncio
import time

import pytest
import nanofaas.runtime.app as runtime


@pytest.mark.parametrize("behavior", ["slow-headers", "no-headers", "endless-body"])
def test_attempt_deadline_closes_socket_and_releases_callback(behavior, monkeypatch):
    manager = runtime.RuntimeWorkManager(1, 2, 2, 4096)
    monkeypatch.setattr(runtime, "_runtime_work", manager)
    monkeypatch.setattr(runtime, "CALLBACK_ATTEMPT_TIMEOUT_SECONDS", 0.1)
    monkeypatch.setattr(runtime, "CALLBACK_MAX_ATTEMPTS", 1)

    async def exercise():
        closed = asyncio.Event()
        connections = set()

        async def peer(reader, writer):
            connections.add(asyncio.current_task())
            try:
                headers = await reader.readuntil(b"\r\n\r\n")
                length = int(next(line.split(b":", 1)[1] for line in headers.split(b"\r\n")
                                  if line.lower().startswith(b"content-length:")))
                await reader.readexactly(length)
                if behavior == "slow-headers":
                    for byte in b"HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n":
                        writer.write(bytes([byte])); await writer.drain()
                        await asyncio.sleep(0.01)
                elif behavior == "endless-body":
                    writer.write(b"HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n")
                    await writer.drain()
                    while True:
                        writer.write(b"x"); await writer.drain(); await asyncio.sleep(0.01)
                else:
                    await reader.read()
            except (ConnectionError, asyncio.IncompleteReadError):
                pass
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except ConnectionError:
                    pass
                closed.set()
                connections.discard(asyncio.current_task())

        server = await asyncio.start_server(peer, "127.0.0.1", 0)
        url = f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}"
        try:
            started = time.monotonic()
            await asyncio.wait_for(runtime.send_callback(url, "deadline", "trace", {}), 0.35)
            assert time.monotonic() - started < 0.3
            await asyncio.wait_for(closed.wait(), 0.2)
            snapshot = manager.snapshot()
            assert snapshot.active_callback_workers == snapshot.pending_callbacks == snapshot.pending_callback_bytes == 0
            reservation = manager.reserve_callback(1)
            reservation.release()
        finally:
            server.close(); await server.wait_closed()
            for connection in tuple(connections):
                connection.cancel()
            await asyncio.gather(*tuple(connections), return_exceptions=True)
            await manager.shutdown(0.5)

    asyncio.run(asyncio.wait_for(exercise(), 1.0))


def test_blocked_upload_is_cancelled_without_waiting_for_peer():
    from nanofaas.runtime.callback_transport import post_callback

    async def exercise():
        accepted = asyncio.Event()
        release = asyncio.Event()
        peers = set()

        async def peer(reader, writer):
            peers.add(asyncio.current_task())
            try:
                await reader.readuntil(b"\r\n\r\n")
                accepted.set()
                # Stop reading so the sender eventually fills the TCP buffers.
                await release.wait()
                await reader.read()
            finally:
                writer.close()
                await writer.wait_closed()
                peers.discard(asyncio.current_task())

        server = await asyncio.start_server(peer, "127.0.0.1", 0, limit=1024)
        url = f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}"
        try:
            started = time.monotonic()
            with pytest.raises(TimeoutError):
                await post_callback(url, b"x" * (32 * 1024 * 1024), {}, 0.1)
            assert accepted.is_set()
            assert time.monotonic() - started < 0.35
        finally:
            release.set()
            server.close()
            await server.wait_closed()
            for peer_task in tuple(peers):
                peer_task.cancel()
            await asyncio.gather(*tuple(peers), return_exceptions=True)

    asyncio.run(asyncio.wait_for(exercise(), 1.0))


def test_worker_wait_has_deadline_and_cancelled_waiter_does_not_consume_capacity():
    manager = runtime.RuntimeWorkManager(1, 1, 3, 4096)

    async def exercise():
        entered = asyncio.Event()
        release = asyncio.Event()
        calls = []

        async def occupied():
            entered.set()
            await release.wait()

        async def queued():
            calls.append("called")

        owner = asyncio.create_task(manager.run_callback_call(occupied))
        await entered.wait()
        try:
            with pytest.raises(TimeoutError):
                async with asyncio.timeout(0.03):
                    await manager.run_callback_call(queued)
            assert calls == []
            assert manager.snapshot().active_callback_workers == 1
            cancelled = asyncio.create_task(manager.run_callback_call(queued))
            await asyncio.sleep(0)
            cancelled.cancel()
            with pytest.raises(asyncio.CancelledError):
                await cancelled
            assert calls == []
        finally:
            release.set()
            await owner
        await manager.run_callback_call(queued)
        assert calls == ["called"]
        assert manager.snapshot().active_callback_workers == 0
        assert (await manager.shutdown(0.5)).drained

    asyncio.run(asyncio.wait_for(exercise(), 1.0))


def test_callback_worker_capacity_can_be_reused_on_new_event_loops():
    manager = runtime.RuntimeWorkManager(1, 2, 3, 4096)

    async def exercise():
        entered = asyncio.Event()
        release = asyncio.Event()
        started = 0

        async def transport():
            nonlocal started
            started += 1
            if started == 2:
                entered.set()
            await release.wait()

        calls = [asyncio.create_task(manager.run_callback_call(transport)) for _ in range(3)]
        await asyncio.wait_for(entered.wait(), 0.2)
        assert started == 2
        assert manager.snapshot().active_callback_workers == 2
        release.set()
        await asyncio.gather(*calls)
        assert started == 3
        assert manager.snapshot().active_callback_workers == 0

    try:
        for _ in range(3):
            asyncio.run(asyncio.wait_for(exercise(), 1.0))
    finally:
        asyncio.run(manager.shutdown(0.5))


def test_redirect_chain_shares_deadline_without_reading_redirect_bodies():
    from nanofaas.runtime.callback_transport import post_callback

    async def exercise():
        requests = []
        peers = set()

        async def peer(reader, writer):
            peers.add(asyncio.current_task())
            try:
                headers = await reader.readuntil(b"\r\n\r\n")
                requests.append(headers.split(b"\r\n", 1)[0])
                length = next((int(line.split(b":", 1)[1]) for line in headers.split(b"\r\n")
                               if line.lower().startswith(b"content-length:")), 0)
                await reader.readexactly(length)
                await asyncio.sleep(0.04)
                writer.write(b"HTTP/1.1 307 Temporary Redirect\r\nLocation: /next\r\nContent-Length: 1000000\r\n\r\n")
                await writer.drain()
                await reader.read()  # No redirect body: the client must close immediately.
            except (ConnectionError, asyncio.IncompleteReadError):
                pass
            finally:
                writer.close()
                try:
                    await writer.wait_closed()
                except ConnectionError:
                    pass
                peers.discard(asyncio.current_task())

        server = await asyncio.start_server(peer, "127.0.0.1", 0)
        try:
            url = f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}"
            started = time.monotonic()
            with pytest.raises(TimeoutError):
                await post_callback(url, b"{}", {}, 0.1)
            assert 2 <= len(requests) <= 3
            assert all(request.startswith(b"POST ") for request in requests)
            assert time.monotonic() - started < 0.3
        finally:
            server.close()
            await server.wait_closed()
            for peer_task in tuple(peers):
                peer_task.cancel()
            await asyncio.gather(*tuple(peers), return_exceptions=True)

    asyncio.run(asyncio.wait_for(exercise(), 1.0))


def test_cancel_on_network_closes_connection_before_capacity_reuse(monkeypatch):
    manager = runtime.RuntimeWorkManager(1, 1, 1, 4096)
    monkeypatch.setattr(runtime, "_runtime_work", manager)
    monkeypatch.setattr(runtime, "CALLBACK_ATTEMPT_TIMEOUT_SECONDS", 1.0)

    async def exercise():
        received = asyncio.Event()
        closed = asyncio.Event()

        async def peer(reader, writer):
            try:
                await reader.readuntil(b"\r\n\r\n")
                received.set()
                await reader.read()
                closed.set()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(peer, "127.0.0.1", 0)
        try:
            url = f"http://127.0.0.1:{server.sockets[0].getsockname()[1]}"
            work = asyncio.create_task(runtime.send_callback(url, "cancel", "trace", {}))
            await asyncio.wait_for(received.wait(), 0.3)
            work.cancel()
            with pytest.raises(asyncio.CancelledError):
                await work
            await asyncio.wait_for(closed.wait(), 0.1)
            snapshot = manager.snapshot()
            assert snapshot.active_callback_workers == snapshot.pending_callbacks == snapshot.pending_callback_bytes == 0
            reservation = manager.reserve_callback(1)
            reservation.release()
        finally:
            server.close()
            await server.wait_closed()
            await manager.shutdown(0.5)

    asyncio.run(asyncio.wait_for(exercise(), 1.0))


@pytest.mark.parametrize("status, attempts", [(204, 1), (400, 1), (408, 3), (429, 3), (503, 3)])
def test_retry_policy_preserves_identity_and_never_overlaps(status, attempts, monkeypatch):
    manager = runtime.RuntimeWorkManager(1, 2, 2, 4096)
    monkeypatch.setattr(runtime, "_runtime_work", manager)
    monkeypatch.setattr(runtime, "CALLBACK_MAX_ATTEMPTS", 3)
    calls = []
    active = 0

    async def transport(url, body, headers, timeout_seconds):
        nonlocal active
        active += 1
        try:
            assert active == 1
            await asyncio.sleep(0)
            calls.append((url, body, headers.copy()))
            return status
        finally:
            active -= 1

    monkeypatch.setattr(runtime.callback_transport, "post_callback", transport)

    async def exercise():
        await runtime.send_callback("http://callback.invalid", "stable-id", "stable-trace", {}, "2")
        assert len(calls) == attempts
        assert all(call == calls[0] for call in calls)
        assert calls[0][0].endswith("/stable-id:complete")
        assert calls[0][2]["X-Trace-Id"] == "stable-trace"
        assert calls[0][2]["X-Dispatch-Attempt"] == "2"
        assert active == manager.snapshot().active_callback_workers == 0
        assert (await manager.shutdown(0.5)).drained

    asyncio.run(exercise())
