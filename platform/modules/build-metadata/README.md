# build-metadata

Optional control-plane module: a small diagnostics endpoint exposing build
information of the running control plane.

## Provides

- `BuildMetadataController` — `GET /modules/build-metadata` returns build/
  version metadata; useful to verify which build is actually running during
  deploys and E2E runs.

## Configuration

None.
