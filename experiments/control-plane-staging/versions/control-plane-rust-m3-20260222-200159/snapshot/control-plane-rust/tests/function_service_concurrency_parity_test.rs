#![allow(non_snake_case)]

use control_plane_rust::model::ExecutionMode;
use control_plane_rust::registry::{
    FunctionDefaults, FunctionRegistry, FunctionService, KubernetesResourceManager,
    NoOpResolverImageValidator, ResolverFunctionSpec,
};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Barrier, Mutex};
use std::thread;
use std::time::Duration;

struct BlockingResourceManager {
    started_tx: Mutex<Option<mpsc::Sender<()>>>,
    allow_rx: Mutex<mpsc::Receiver<()>>,
    provision_calls: AtomicUsize,
    deprovision_calls: AtomicUsize,
}

impl KubernetesResourceManager for BlockingResourceManager {
    fn provision(&self, _spec: &ResolverFunctionSpec) -> String {
        self.provision_calls.fetch_add(1, Ordering::SeqCst);
        if let Some(tx) = self.started_tx.lock().expect("started tx").take() {
            let _ = tx.send(());
        }
        let _ = self.allow_rx.lock().expect("allow rx").recv();
        "http://fn-svc:8080".to_string()
    }

    fn deprovision(&self, _name: &str) {
        self.deprovision_calls.fetch_add(1, Ordering::SeqCst);
    }

    fn set_replicas(&self, _name: &str, _replicas: i32) {}
}

fn spec(name: &str, image: &str, mode: ExecutionMode) -> ResolverFunctionSpec {
    let mut spec = ResolverFunctionSpec::new(name, image);
    spec.execution_mode = Some(mode);
    spec
}

#[test]
fn register_withSameName_onlyOneSucceeds() {
    let service = Arc::new(FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    ));

    let threads = 10usize;
    let barrier = Arc::new(Barrier::new(threads));
    let success = Arc::new(AtomicUsize::new(0));
    let winner = Arc::new(Mutex::new(Vec::<String>::new()));
    let mut handles = vec![];

    for i in 0..threads {
        let service = service.clone();
        let barrier = barrier.clone();
        let success = success.clone();
        let winner = winner.clone();
        handles.push(thread::spawn(move || {
            barrier.wait();
            let result =
                service.register(spec("myFunc", &format!("image-{i}"), ExecutionMode::Local));
            if let Some(value) = result {
                success.fetch_add(1, Ordering::SeqCst);
                winner.lock().expect("winner").push(value.image);
            }
        }));
    }
    for handle in handles {
        handle.join().expect("join");
    }

    assert_eq!(1, success.load(Ordering::SeqCst));
    assert_eq!(1, winner.lock().expect("winner").len());
    assert_eq!(
        winner.lock().expect("winner")[0].as_str(),
        service.get("myFunc").expect("registered").image.as_str()
    );
}

#[test]
fn register_withDifferentNames_allSucceed() {
    let service = Arc::new(FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    ));
    let threads = 10usize;
    let barrier = Arc::new(Barrier::new(threads));
    let success = Arc::new(AtomicUsize::new(0));
    let mut handles = vec![];

    for i in 0..threads {
        let service = service.clone();
        let barrier = barrier.clone();
        let success = success.clone();
        handles.push(thread::spawn(move || {
            barrier.wait();
            if service
                .register(spec(
                    &format!("func-{i}"),
                    &format!("image-{i}"),
                    ExecutionMode::Local,
                ))
                .is_some()
            {
                success.fetch_add(1, Ordering::SeqCst);
            }
        }));
    }
    for handle in handles {
        handle.join().expect("join");
    }

    assert_eq!(threads, success.load(Ordering::SeqCst));
    assert_eq!(threads, service.list().len());
}

#[test]
fn register_existingFunction_returnsEmpty() {
    let service = FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );

    let result1 = service.register(spec("myFunc", "image1", ExecutionMode::Local));
    let result2 = service.register(spec("myFunc", "image2", ExecutionMode::Local));

    assert!(result1.is_some());
    assert!(result2.is_none());
    assert_eq!(
        "image1",
        service.get("myFunc").expect("function").image.as_str()
    );
}

#[test]
fn registerAndRemove_sameName_areSerialized() {
    let (started_tx, started_rx) = mpsc::channel();
    let (allow_tx, allow_rx) = mpsc::channel();
    let resource_manager = Arc::new(BlockingResourceManager {
        started_tx: Mutex::new(Some(started_tx)),
        allow_rx: Mutex::new(allow_rx),
        provision_calls: AtomicUsize::new(0),
        deprovision_calls: AtomicUsize::new(0),
    });
    let service = Arc::new(FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        Some(resource_manager.clone()),
        Arc::new(NoOpResolverImageValidator),
        vec![],
    ));
    let spec = spec("race-fn", "img:latest", ExecutionMode::Deployment);

    let register_service = service.clone();
    let register_handle = thread::spawn(move || register_service.register(spec));

    started_rx
        .recv_timeout(Duration::from_secs(5))
        .expect("provision started");

    let remove_done = Arc::new(AtomicBool::new(false));
    let remove_done_signal = remove_done.clone();
    let remove_service = service.clone();
    let remove_handle = thread::spawn(move || {
        let result = remove_service.remove("race-fn");
        remove_done_signal.store(true, Ordering::SeqCst);
        result
    });

    thread::sleep(Duration::from_millis(150));
    assert!(!remove_done.load(Ordering::SeqCst));

    allow_tx.send(()).expect("allow provision");

    assert!(register_handle.join().expect("register join").is_some());
    assert!(remove_handle.join().expect("remove join").is_some());
    assert!(service.get("race-fn").is_none());
    assert_eq!(1, resource_manager.provision_calls.load(Ordering::SeqCst));
    assert_eq!(1, resource_manager.deprovision_calls.load(Ordering::SeqCst));
}
