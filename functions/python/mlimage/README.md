# image-classification NanoFaaS function

This directory contains the source code for the `image-classification` function
for NanoFaaS. It implements a simple Python function for image classification
using Torchvision.

The function logic is primarily derived from the DFaaS function version
available here: https://github.com/unimib-datAI/dfaas/tree/main/functions/image-classification

The main function logic can be found in [handler.py](./handler.py).

## Build and deploy function

Run `nanofaas-cli` with:

```console
$ nanofaas-cli deploy --runtime podman --file function.yaml
```

You must have a NanoFaaS platform reachable and manually move the container
image to the appropriate registry, depending on the chosen deployment. For
example, if you run Kubernetes locally and use Podman as the runtime, as in the
command above:

```console
$ podman save -o mlimage.tar localhost/nanofaas/mlimage:latest && sudo k3s ctr image import mlimage.tar && rm mlimage.tar
$ kubectl patch deploy fn-mlimage -p '{"spec":{"template":{"spec":{"containers":[{"name":"function","imagePullPolicy":"Never"}]}}}}'
$ sudo kubectl rollout restart deploy fn-mlimage
```

The patch is required because, by default, NanoFaaS tries to pull the image from
a public registry.

## Local development

Run `uv` with:

```console
$ HANDLER_MODULE=handler uv run -m uvicorn nanofaas.runtime.app:app --host 0.0.0.0 --port 8080
```

Test with:

```console
$ curl http://localhost:8080/invoke -X POST --data @payloads/vulture_request.json -H "X-Execution-ID: ttt" -i
HTTP/1.1 200 OK
date: Thu, 30 Jul 2026 16:14:31 GMT
server: uvicorn
x-cold-start: true
x-init-duration-ms: 4343
content-length: 90
content-type: application/json

{"statusCode":200,"body":"[{\"class\": \"church\", \"probability\": 0.5182393789291382}]"}
```

## Body request

You must encode input images as a base64 JSON string. See the examples in the
[payloads](./payloads) directory.

## Example response

```console
$ nanofaas-cli invoke -d @payloads/vulture_request.json mlimage | jq
{
  "executionId": "a91f3d7a-29c3-4fd8-b7f3-8d16045bd2ff",
  "status": "success",
  "output": {
    "statusCode": 200,
    "body": "[{\"class\": \"church\", \"probability\": 0.5182393789291382}]"
  },
  "error": null
}
```
