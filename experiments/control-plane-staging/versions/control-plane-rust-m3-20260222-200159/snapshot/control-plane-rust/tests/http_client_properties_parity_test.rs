#![allow(non_snake_case)]

use control_plane_rust::config::HttpClientProperties;

#[test]
fn constructor_withNullValues_appliesDefaults() {
    let properties = HttpClientProperties::new(None, None, None);
    assert_eq!(properties.connect_timeout_ms, 5000);
    assert_eq!(properties.read_timeout_ms, 30000);
    assert_eq!(properties.max_in_memory_size_mb, 1);
}

#[test]
fn constructor_withNonPositiveValues_appliesDefaults() {
    let properties = HttpClientProperties::new(Some(0), Some(-1), Some(0));
    assert_eq!(properties.connect_timeout_ms, 5000);
    assert_eq!(properties.read_timeout_ms, 30000);
    assert_eq!(properties.max_in_memory_size_mb, 1);
}

#[test]
fn constructor_withPositiveValues_keepsProvidedValues() {
    let properties = HttpClientProperties::new(Some(1500), Some(4200), Some(8));
    assert_eq!(properties.connect_timeout_ms, 1500);
    assert_eq!(properties.read_timeout_ms, 4200);
    assert_eq!(properties.max_in_memory_size_mb, 8);
}
