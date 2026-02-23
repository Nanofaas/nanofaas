#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModuleDescriptor {
    pub name: String,
    pub configuration_classes: Vec<String>,
}

pub fn main_with_runner<F>(args: &[String], runner: F)
where
    F: Fn(&[String]),
{
    runner(args);
}

pub fn select_imports(modules: &[ModuleDescriptor]) -> Vec<String> {
    let mut ordered = Vec::new();
    for module in modules {
        for class in &module.configuration_classes {
            if !ordered.contains(class) {
                ordered.push(class.clone());
            }
        }
    }
    ordered
}
