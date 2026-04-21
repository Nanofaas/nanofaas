import { createRuntime } from "nanofaas-function-sdk";

import { handleWordStats } from "./handler.js";

const runtime = createRuntime();
runtime.register("word-stats", handleWordStats);

await runtime.start();
