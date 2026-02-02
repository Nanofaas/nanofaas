"""nanofaas Python Function Runtime"""
import importlib
import logging
import os

from flask import Flask, request, jsonify
import requests as http_requests

logging.basicConfig(
    level=logging.INFO,
    format='{"timestamp":"%(asctime)s","level":"%(levelname)s","message":"%(message)s"}'
)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Configuration
CALLBACK_URL = os.environ.get('CALLBACK_URL', '')
DEFAULT_EXECUTION_ID = os.environ.get('EXECUTION_ID', '')
HANDLER_MODULE = os.environ.get('HANDLER_MODULE', 'handler')
HANDLER_FUNCTION = os.environ.get('HANDLER_FUNCTION', 'handle')

# Handler cache
_handler = None


def get_handler():
    global _handler
    if _handler is None:
        try:
            module = importlib.import_module(HANDLER_MODULE)
            _handler = getattr(module, HANDLER_FUNCTION)
            logger.info(f"Loaded handler: {HANDLER_MODULE}:{HANDLER_FUNCTION}")
        except Exception as e:
            logger.error(f"Failed to load handler: {e}")
            raise
    return _handler


@app.route('/health', methods=['GET'])
def health():
    return jsonify({"status": "ok"})


@app.route('/invoke', methods=['POST'])
def invoke():
    # Get execution ID from header (warm mode) or ENV (one-shot)
    execution_id = request.headers.get('X-Execution-Id') or DEFAULT_EXECUTION_ID
    trace_id = request.headers.get('X-Trace-Id')
    callback_url = request.headers.get('X-Callback-Url') or CALLBACK_URL

    if not execution_id:
        return jsonify({"error": "No execution ID provided"}), 400

    try:
        payload = request.get_json()
        handler = get_handler()
        result = handler(payload)

        # Send callback (best effort)
        if callback_url:
            _send_callback(callback_url, execution_id, trace_id, {
                "success": True,
                "output": result,
                "error": None
            })

        return jsonify(result)

    except Exception as e:
        logger.exception(f"Handler error: {e}")

        if callback_url:
            _send_callback(callback_url, execution_id, trace_id, {
                "success": False,
                "output": None,
                "error": {"code": "HANDLER_ERROR", "message": str(e)}
            })

        return jsonify({"error": str(e)}), 500


def _send_callback(callback_url: str, execution_id: str, trace_id: str, result: dict):
    try:
        url = f"{callback_url}/{execution_id}:complete"
        headers = {"Content-Type": "application/json"}
        if trace_id:
            headers["X-Trace-Id"] = trace_id

        resp = http_requests.post(url, json=result, headers=headers, timeout=10)
        if not resp.ok:
            logger.warning(f"Callback failed: {resp.status_code}")
    except Exception as e:
        logger.warning(f"Callback error: {e}")


if __name__ == '__main__':
    port = int(os.environ.get('PORT', 8080))
    app.run(host='0.0.0.0', port=port)
