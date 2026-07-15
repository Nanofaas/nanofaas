import { createRuntime } from "nanofaas-function-sdk";

import { handleRomanNumeral } from "./handler.js";

const runtime = createRuntime();
runtime.register("roman-numeral", handleRomanNumeral);

await runtime.start();
