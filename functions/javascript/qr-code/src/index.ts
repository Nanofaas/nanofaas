import { createRuntime } from "nanofaas-function-sdk";
import { handleQRCode } from "./handler.js";

const runtime = createRuntime();
runtime.register("qr-code", handleQRCode);
await runtime.start();
