# Function definition

Each NanoFaaS function is described by a YAML manifest file that defines the
function specification and the instructions needed to build it. The NanoFaaS
CLI reads this manifest when running the `deploy` command.

The manifest has two main sections: the first lives at the YAML root level and
contains the general function fields; the second lives under the `x-cli.build`
section and is used by the NanoFaaS CLI when deploying the function with:

```console
$ nanofaas deploy -f function.yaml
```

## Function manifest fields

The following fields are used by the NanoFaaS control plane to manage the
function:

| Field           | Type    | Required | Purpose                                                            | Example                          | Notes                                                  |
|-----------------|---------|---------:|--------------------------------------------------------------------|----------------------------------|--------------------------------------------------------|
| `name`          | string  | yes      | Function name used by the control plane and CLI commands           | `echo`                           | Must identify the function uniquely within a namespace |
| `image`         | string  | yes      | Container image to deploy                                          | `ghcr.io/miciav/nanofaas/echo:1` | Must not be blank                                      |
| `timeoutMs`     | integer | no       | Default request timeout for the function                           | `10000`                          | Used for invocation and runtime behavior               |
| `concurrency`   | integer | no       | Maximum concurrent requests or instances, depending on the runtime | `2`                              | Defaults to the control plane setting if omitted       |
| `executionMode` | string  | no       | Tells NanoFaaS how the function should run                         | `DEPLOYMENT`                     | Common values include `DEPLOYMENT` and `EXTERNAL`          |

## CLI-specific build metadata

The CLI reads the `x-cli.build` section when deploying the function:

| Field                    | Type               | Required | Purpose                                            | Example            | Notes                                       |
|--------------------------|--------------------|---------:|----------------------------------------------------|--------------------|---------------------------------------------|
| `x-cli.build.context`    | string             | yes      | Build context directory for Docker                 | `.`                | Required by the CLI when building the image |
| `x-cli.build.dockerfile` | string             | no       | Path to the Dockerfile                             | `Dockerfile`       | Defaults to `Dockerfile` if omitted         |
| `x-cli.build.platform`   | string             | no       | Target platform for `docker buildx`                | `linux/amd64`      | Optional                                    |
| `x-cli.build.push`       | boolean            | no       | Whether the CLI should push the image after build  | `true`             | Defaults to `true`                           |
| `x-cli.build.buildArgs`  | map[string,string] | no       | Build-time arguments passed to Docker               | `VERSION: "1.2.3"` | Optional key/value map                      |

## Examples

Minimal example:

```yaml
name: echo
image: registry.example/echo:1
x-cli:
  build:
    context: .
```

A more complex example:

```yaml
name: roman-numeral
image: nanofaas/roman-numeral:latest
timeoutMs: 10000
concurrency: 2
executionMode: DEPLOYMENT

x-cli:
  build:
    context: ../../..
    dockerfile: functions/bash/roman-numeral/Dockerfile
    platform: linux/amd64
    push: true
```
