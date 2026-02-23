use crate::queue::InvocationTask;

pub trait SyncQueueGateway {
    fn enqueue_or_throw(&self, task: Option<&InvocationTask>);

    fn enabled(&self) -> bool;

    fn retry_after_seconds(&self) -> i32;
}

#[derive(Debug)]
pub struct NoOpSyncQueueGateway;

impl SyncQueueGateway for NoOpSyncQueueGateway {
    fn enqueue_or_throw(&self, _task: Option<&InvocationTask>) {
        panic!("Sync queue module not loaded")
    }

    fn enabled(&self) -> bool {
        false
    }

    fn retry_after_seconds(&self) -> i32 {
        2
    }
}

static NO_OP_SYNC_QUEUE_GATEWAY: NoOpSyncQueueGateway = NoOpSyncQueueGateway;

pub fn no_op_sync_queue_gateway() -> &'static NoOpSyncQueueGateway {
    &NO_OP_SYNC_QUEUE_GATEWAY
}
