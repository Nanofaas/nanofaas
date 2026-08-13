import { createRuntime } from "nanofaas-function-sdk";
import { handle } from "./handler.js";

const runtime = createRuntime();
runtime.register("handler-envelope", handle);
await runtime.start();
