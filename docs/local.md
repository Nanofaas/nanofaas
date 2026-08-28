# Local development

Local development can be done directly with Gradle. The Docker backend provider
is recommended as an alternative to the Kubernetes-based deployment backend.

The following procedure shows how to start the control plane in development mode
and deploy a function with `nanofaas-cli`.

> [!IMPORTANT]
> Docker must be installed and available to non-root users. Follow the [Linux
> post-installation steps for Docker
> Engine](https://docs.docker.com/engine/install/linux-postinstall/manage-docker-as-a-non-root-user)
> to configure Docker correctly.

> [!NOTE]
> Unless specified otherwise, run all commands from the root directory of the
> NanoFaaS project!

## Start a local image registry

The NanoFaaS control plane requires access to an image registry to pull function
images during deployment. By default, NanoFaaS uses the Docker default registry,
Docker Hub. During local development, you may not be able to push images to
Docker Hub. For local development, use a local image registry without
authentication. The registry must be available at `localhost:5000`.

Follow these commands to start the local image registry:

```bash
$ docker volume create registry-data
$ docker run -d --name registry --restart=always \
    -p 5000:5000 \
    -v registry-data:/var/lib/registry \
    registry:3
```

## Start the control plane

Start the control plane with the local Docker backend:

```bash
$ ./gradlew :control-plane:bootRun \
    -PcontrolPlaneModules=container-deployment-provider \
    --args='--logging.level.root=DEBUG --nanofaas.deployment.default-backend=container-local --spring.output.ansi.enabled=ALWAYS --logging.level.java.lang.ProcessBuilder=INFO'
```

The `DEBUG` log level is intentional and can help during development.

Gradle continues to run until you press `CTRL+C`.

## Build and push a function image

Choose a function and build it with the following command. This example uses
`go/qr-code`:

```bash
$ docker build \
    -t 127.0.0.1:5000/nanofaas/go-qr-code:e2e \
    -f functions/go/qr-code/Dockerfile .
```

Push the image to the local registry:

```bash
$ docker push 127.0.0.1:5000/nanofaas/go-qr-code:e2e
```

You can check the registry contents with:

```bash
$ curl http://127.0.0.1:5000/v2/_catalog
```

Example output:

```text
{"repositories":["nanofaas/go-qr-code"]}
```

## Deploy the function

After you push the image, apply the function definition to the control plane
with `nanofaas-cli`:

```bash
$ ./gradlew :nanofaas-cli:run \
    --args='fn apply --file /home/<user>/nanofaas/functions/go/qr-code/function.yaml'
```

**Important:** verify the complete image name in the function definition. The
image registry must be specified as the image domain. For example:

```yaml
name: qr-code-go
image: 127.0.0.1:5000/nanofaas/go-qr-code:e2e
timeoutMs: 10000
concurrency: 2
executionMode: DEPLOYMENT
...
```

In this example, `127.0.0.1:5000` is the local Docker registry. The image name
must match the image that you built and pushed to this registry.

## Check the deployed function

After you deploy the function, you can use `nanofaas-cli` to verify the
deployment. In this example, the function is `qr-code-go`.

Run the `fn list` command and the `fn get qr-code-go` command:

```bash
$ ./gradlew --quiet :nanofaas-cli:run --args='fn list'
qr-code-go      127.0.0.1:5000/nanofaas/go-qr-code:e2e
```

```bash
$ ./gradlew --quiet :nanofaas-cli:run --args='fn get qr-code-go'
{
  "name": "qr-code-go",
  "image": "127.0.0.1:5000/nanofaas/go-qr-code:e2e",
  "command": [],
  "env": {},
  "timeoutMs": 10000,
  "concurrency": 2,
  "queueSize": 100,
  "maxRetries": 3,
  "endpointUrl": "http://127.0.0.1:22483/invoke",
  "requestedExecutionMode": "DEPLOYMENT",
  "effectiveExecutionMode": "DEPLOYMENT",
  "deploymentBackend": "container-local",
  "scalingConfig": {
    "strategy": "INTERNAL",
    "minReplicas": 1,
    "maxReplicas": 10,
    "metrics": [
      {
        "type": "queue_depth",
        "target": "5",
        "query": null
      }
    ],
    "concurrencyControl": {
      "mode": "FIXED",
      "targetInFlightPerPod": null,
      "minTargetInFlightPerPod": null,
      "maxTargetInFlightPerPod": null,
      "upscaleCooldownMs": null,
      "downscaleCooldownMs": null,
      "highLoadThreshold": null,
      "lowLoadThreshold": null,
      "targetLatencyMs": null,
      "weight": null
    }
  },
  "deploymentObjects": {
    "containerNamePrefix": "nanofaas-qr-code-go"
  }
}
```

## Invoke the function

First, create a JSON input file in a local directory. For example, for
`qr-code-go`, create `payload.json` with the following content:

```json
{"text": "https://example.org/default"}
```

You can then use the `nanofaas-cli invoke` command:

```bash
$ ./gradlew --quiet :nanofaas-cli:run --args='invoke qr-code-go --data @/home/<user>/nanofaas/payload.json'
{
  "executionId": "f7d2d18d-c8d4-4412-a0fe-c3332188a31d",
  "status": "success",
  "output": "iVBORw0KGgoAAAANSUhEUgAAAQAAAAEAAQMAAABmvDolAAAABlBMVEX///8AAABVwtN+AAABlklEQVR42uyYPZKEIBCFHzWBIUfwKB5NjsZRPIKhgcXbeg06u1NuurV00YE1wjeB/UM3D8OGDfu3Fik7sBCYdmAt95IrYAPwOmwHmPMajnvJFbCwvOQHnhO3hQLqkk8g8JAfClwDqb6vcApYCkcmq+QMPqd970A9kGJewznttXifDrHOgdvWQNsKxy+tp2sgbm0r0fygvqNnXwDmjGI/7s/Uf95J6wPQWj1p8SI3iJ5JnvAFbEsKJ2JGaygKt2LvD0BrKBoPEuUNJbovQIl8TsxQuJlRNNl+jO5OAHJXvXLHkoKeHx3HAWBZPamE2wute36kvQeg2BRkn66sVri9AcDCgqkGndQZZc93S3IBtC4adTXRFgJpQfcFyA+KrXlDxWvqgTsg1ibDfN3KLOqpAD0B94GbTBwAQqvQwxVwK1o2+MxMpoGkcMIX0G7NCrdNQeaHB3Ggd+CSetp1pWb195uaI8DEgauSn7QgHwBsgI9N/PAIVPnKRr59Ntln+zkmuQCaosU68l2Kljdg2LBhf25fAQAA//96i8qMbMUK0wAAAABJRU5ErkJggg==",
  "error": null,
  "statusCode": 200,
  "headers": {
    "Content-type": "image/png"
  },
  "encoding": "base64"
}
```

> [!IMPORTANT]
> Use an absolute path to the input file. If you use a relative path,
> `nanofaas-cli` cannot read the file when ran with Gradle.

## Run the Java debugger

You can run the control plane or `nanofaas-cli` in Java debug mode to debug the
application.

Add the `--debug-jvm` option to the Gradle command. For example:

```bash
$ ./gradlew :control-plane:bootRun --debug-jvm
```

When the application starts, open another terminal and attach the Java debugger
to port `5005`:

```bash
$ /home/<user>/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2/bin/jdb -attach 5005
```

The JDK path can vary between systems. To find the JDKs available to Gradle,
run:

```bash
$ ./gradlew --quiet javaToolchains
```

Select the JDK that uses Java 25 and use its path when you start `jdb`.
