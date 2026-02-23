#![allow(non_snake_case)]

use control_plane_rust::config::{FsResourceProvider, RuntimeHints, VertxResourceHints};
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

fn temp_dir(prefix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_nanos();
    let dir = std::env::temp_dir().join(format!("control-plane-rust-{prefix}-{nanos}"));
    fs::create_dir_all(&dir).expect("create temp dir");
    dir
}

#[test]
fn findClassesInPackage_collectsFromDirectoryAndSkipsMetadataClasses() {
    let root = temp_dir("hints-branch-find");
    let package_dir = root.join("demo/pkg");
    fs::create_dir_all(&package_dir).expect("package dir");
    fs::write(package_dir.join("Alpha.class"), []).expect("alpha");
    fs::write(package_dir.join("Beta$Inner.class"), []).expect("beta");
    fs::write(package_dir.join("package-info.class"), []).expect("package-info");
    fs::write(package_dir.join("module-info.class"), []).expect("module-info");

    let provider = FsResourceProvider::new(root);
    let classes = provider
        .list_classes_in_package("demo.pkg")
        .expect("classes");

    assert!(classes.contains(&"demo.pkg.Alpha".to_string()));
    assert!(classes.contains(&"demo.pkg.Beta$Inner".to_string()));
    assert!(!classes.contains(&"demo.pkg.package-info".to_string()));
    assert!(!classes.contains(&"demo.pkg.module-info".to_string()));
}

#[test]
fn registerTypeHint_handlesInterfaceAndMissingClassSafely() {
    let mut hints = RuntimeHints::default();

    VertxResourceHints::register_type_hint(&mut hints, "java.lang.Runnable");
    VertxResourceHints::register_type_hint(&mut hints, "com.example.DoesNotExist");
    VertxResourceHints::register_type_hint(&mut hints, "java.lang.String");

    assert!(!hints.has_type_hint("java.lang.Runnable"));
    assert!(hints.has_type_hint("java.lang.String"));
}
