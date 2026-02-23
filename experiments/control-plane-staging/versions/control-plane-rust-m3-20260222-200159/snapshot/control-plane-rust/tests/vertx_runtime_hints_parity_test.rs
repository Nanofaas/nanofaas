#![allow(non_snake_case)]

use control_plane_rust::config::{
    MemberCategory, ResourceProvider, RuntimeHints, VertxResourceHints,
};

#[derive(Debug)]
struct FailingProvider;

impl ResourceProvider for FailingProvider {
    fn list_classes_in_package(&self, _package: &str) -> Result<Vec<String>, String> {
        Err("simulated".to_string())
    }
}

#[derive(Debug)]
struct EmptyProvider;

impl ResourceProvider for EmptyProvider {
    fn list_classes_in_package(&self, _package: &str) -> Result<Vec<String>, String> {
        Ok(vec![])
    }
}

#[test]
fn registerHints_registersVertxAndCoreFabric8Types() {
    let mut hints = RuntimeHints::default();
    let provider = EmptyProvider;

    VertxResourceHints.register_hints(&mut hints, &provider);

    assert!(hints.has_resource("META-INF/vertx/vertx-version.txt"));
    assert!(hints.has_type_member_category(
        "io.fabric8.kubernetes.api.model.Pod",
        MemberCategory::InvokePublicMethods
    ));
    assert!(hints.has_type_member_category(
        "io.fabric8.kubernetes.api.model.DeleteOptions",
        MemberCategory::InvokePublicMethods
    ));
    assert!(hints.has_type_member_category(
        "io.fabric8.kubernetes.client.impl.KubernetesClientImpl",
        MemberCategory::InvokeDeclaredConstructors
    ));
    assert!(hints.type_hints_count() > 2);
}

#[test]
fn registerHints_whenClassloaderEnumerationFails_keepsBaseHints() {
    let mut hints = RuntimeHints::default();
    let provider = FailingProvider;

    VertxResourceHints.register_hints(&mut hints, &provider);

    assert!(hints.has_type_member_category(
        "io.fabric8.kubernetes.api.model.Pod",
        MemberCategory::InvokePublicMethods
    ));
    assert!(hints.has_type_member_category(
        "io.fabric8.kubernetes.api.model.DeleteOptions",
        MemberCategory::InvokePublicMethods
    ));
}
