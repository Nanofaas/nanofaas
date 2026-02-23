#![allow(non_snake_case)]

use control_plane_rust::registry::FunctionNotFoundError;

#[test]
fn defaultConstructor_hasNoMessage() {
    let ex = FunctionNotFoundError::new();
    assert!(ex.message().is_none());
}

#[test]
fn functionNameConstructor_includesFunctionNameInMessage() {
    let ex = FunctionNotFoundError::with_function_name("echo");
    assert_eq!(ex.message(), Some("Function not found: echo"));
}
