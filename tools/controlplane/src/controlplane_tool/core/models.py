from typing import Literal

FunctionRuntimeKind = Literal[
    "java", "java-lite", "go", "python", "exec", "javascript", "fixture"
]
