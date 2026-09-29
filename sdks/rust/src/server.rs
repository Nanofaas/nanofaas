//! The HTTP/1 connection loop. It replaces `axum::serve` to bound every wait on a connection, as
//! the Go SDK's `http.Server` does: `ReadHeaderTimeout` becomes hyper's header-read timeout, and a
//! peer that accepts no response bytes for the same duration is cut off, so neither a half-sent
//! request nor a client that stops reading can pin a connection or its retained output.

use std::future::Future;
use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};
use std::time::Duration;

use axum::Router;
use hyper::server::conn::http1;
use hyper_util::rt::{TokioIo, TokioTimer};
use hyper_util::service::TowerToHyperService;
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;
use tokio::task::JoinSet;
use tokio::time::Sleep;

/// Serves `router` until `stop` turns true, then asks every connection to finish its in-flight
/// request and waits for them. Dropping this future aborts every connection task.
pub(crate) async fn run(
    listener: TcpListener,
    router: Router,
    io_timeout: Duration,
    mut stop: watch::Receiver<bool>,
) {
    let mut connections = JoinSet::new();
    let connection_stop = stop.clone();
    loop {
        tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, _)) => {
                    connections.spawn(connection(
                        stream,
                        router.clone(),
                        io_timeout,
                        connection_stop.clone(),
                    ));
                }
                // Typically out of file descriptors: back off instead of spinning.
                Err(_) => tokio::time::sleep(Duration::from_millis(100)).await,
            },
            () = stopped(&mut stop) => break,
        }
        while connections.try_join_next().is_some() {}
    }
    drop(listener);
    while connections.join_next().await.is_some() {}
}

async fn connection(
    stream: TcpStream,
    router: Router,
    io_timeout: Duration,
    mut stop: watch::Receiver<bool>,
) {
    let io = TokioIo::new(WriteDeadline::new(stream, io_timeout));
    let connection = http1::Builder::new()
        .timer(TokioTimer::new())
        .header_read_timeout(io_timeout)
        .serve_connection(io, TowerToHyperService::new(router));
    tokio::pin!(connection);
    tokio::select! {
        _ = connection.as_mut() => return,
        () = stopped(&mut stop) => {}
    }
    connection.as_mut().graceful_shutdown();
    let _ = connection.await;
}

/// Resolves once `stop` turns true, or its sender is gone.
async fn stopped(stop: &mut watch::Receiver<bool>) {
    let _ = stop.wait_for(|stopped| *stopped).await;
}

/// Fails a write that has made no progress for `timeout`.
struct WriteDeadline {
    inner: TcpStream,
    timeout: Duration,
    stalled: Option<Pin<Box<Sleep>>>,
}

impl WriteDeadline {
    fn new(inner: TcpStream, timeout: Duration) -> Self {
        Self {
            inner,
            timeout,
            stalled: None,
        }
    }

    fn guard<T>(
        &mut self,
        cx: &mut Context<'_>,
        polled: Poll<io::Result<T>>,
    ) -> Poll<io::Result<T>> {
        if polled.is_ready() {
            self.stalled = None;
            return polled;
        }
        let timeout = self.timeout;
        let stalled = self
            .stalled
            .get_or_insert_with(|| Box::pin(tokio::time::sleep(timeout)));
        match stalled.as_mut().poll(cx) {
            Poll::Ready(()) => Poll::Ready(Err(io::ErrorKind::TimedOut.into())),
            Poll::Pending => Poll::Pending,
        }
    }
}

impl AsyncRead for WriteDeadline {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_read(cx, buf)
    }
}

impl AsyncWrite for WriteDeadline {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        data: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let polled = Pin::new(&mut this.inner).poll_write(cx, data);
        this.guard(cx, polled)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let polled = Pin::new(&mut this.inner).poll_flush(cx);
        this.guard(cx, polled)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let polled = Pin::new(&mut this.inner).poll_shutdown(cx);
        this.guard(cx, polled)
    }
}
