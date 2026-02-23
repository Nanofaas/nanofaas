#![allow(non_snake_case)]

use control_plane_rust::application::{select_imports, ModuleDescriptor};

#[test]
fn importSelectorDiscoversTestModuleConfiguration() {
    let modules = vec![ModuleDescriptor {
        name: "test-module".to_string(),
        configuration_classes: vec![
            "it.unimib.datai.nanofaas.controlplane.TestModuleConfiguration".to_string(),
        ],
    }];

    let imports = select_imports(&modules);
    assert!(imports
        .contains(&"it.unimib.datai.nanofaas.controlplane.TestModuleConfiguration".to_string()));
}
