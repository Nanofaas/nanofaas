import { createRuntime } from "nanofaas-function-sdk";
import { handleJsonTransform } from "./handler.js";
const runtime = createRuntime();
runtime.register("json-transform", handleJsonTransform);
await runtime.start();
