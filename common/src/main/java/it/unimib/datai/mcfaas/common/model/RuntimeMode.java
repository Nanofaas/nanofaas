package it.unimib.datai.mcfaas.common.model;

/**
 * Specifies how the watchdog should interact with the function process.
 */
public enum RuntimeMode {
    /**
     * Function exposes an HTTP server.
     * Watchdog polls /health, then POST /invoke.
     * Best for: Java Spring Boot, Python FastAPI/Flask, Node.js Express
     */
    HTTP,

    /**
     * Function reads JSON from stdin, writes JSON to stdout.
     * Best for: Python scripts, Node.js scripts, simple CLI tools
     */
    STDIO,

    /**
     * Function reads from input file, writes to output file.
     * Best for: Bash scripts, legacy binaries
     */
    FILE
}
