import os
import importlib
import asyncio
import logging
from fastapi import FastAPI, Request, HTTPException, Header, BackgroundTasks
from fastapi.responses import JSONResponse
from nanofaas.sdk import context, decorator, logging as sdk_logging
import requests

# Set up logging early
sdk_logging.configure_logging()
logger = logging.getLogger(__name__)

app = FastAPI(title="nanoFaaS Python Runtime")

CALLBACK_URL = os.environ.get('CALLBACK_URL', '')
DEFAULT_EXECUTION_ID = os.environ.get('EXECUTION_ID', '')
HANDLER_MODULE = os.environ.get('HANDLER_MODULE')

@app.on_event("startup")
async def startup_event():
    if HANDLER_MODULE:
        try:
            logger.info(f"Loading handler module: {HANDLER_MODULE}")
            importlib.import_module(HANDLER_MODULE)
            if not decorator.get_registered_handler():
                logger.warning(f"Module {HANDLER_MODULE} loaded but no function decorated with @nanofaas_function")
            else:
                logger.info("Successfully registered handler")
        except Exception as e:
            logger.error(f"Failed to load handler module {HANDLER_MODULE}: {e}", exc_info=True)

def send_callback(callback_url: str, execution_id: str, trace_id: str | None, result: dict):
    if not callback_url:
        return
    
    url = f"{callback_url.rstrip('/')}/{execution_id}:complete"
    headers = {"Content-Type": "application/json"}
    if trace_id:
        headers["X-Trace-Id"] = trace_id
        
    logger.info(f"Sending callback to {url}")
    try:
        # Simple retry logic for callback
        for attempt in range(3):
            try:
                resp = requests.post(url, json=result, headers=headers, timeout=5)
                if resp.status_code < 400:
                    logger.info("Callback sent successfully")
                    return
                logger.warning(f"Callback failed with status {resp.status_code} (attempt {attempt+1})")
            except Exception as e:
                logger.warning(f"Callback error: {e} (attempt {attempt+1})")
            
            if attempt < 2:
                time_sleep = [0.1, 0.5, 2.0][attempt]
                import time
                time.sleep(time_sleep)
    except Exception:
        logger.error("Callback failed after all retries")

@app.post("/invoke")
async def invoke(
    request: Request,
    background_tasks: BackgroundTasks,
    x_execution_id: str | None = Header(None),
    x_trace_id: str | None = Header(None),
    x_callback_url: str | None = Header(None)
):
    execution_id = x_execution_id or DEFAULT_EXECUTION_ID
    trace_id = x_trace_id
    callback_url = x_callback_url or CALLBACK_URL
    
    if not execution_id:
        raise HTTPException(status_code=400, detail="Execution ID required")
        
    context.set_context(execution_id, trace_id)
    handler = decorator.get_registered_handler()
    
    if not handler:
        logger.error("No handler registered")
        raise HTTPException(status_code=500, detail="No function registered with @nanofaas_function")

    try:
        payload = await request.json()
        # Java SDK expects the whole body as InvocationRequest, which has 'input' and 'metadata'
        # The existing python-runtime app.py used request.get_json() which is the whole body
        # Let's be consistent with Java SDK: it passes 'input' to the handler
        input_data = payload.get("input") if isinstance(payload, dict) else payload
        
        logger.info(f"Invoking handler for execution {execution_id}")
        
        if asyncio.iscoroutinefunction(handler):
            output = await handler(input_data)
        else:
            output = handler(input_data)
            
        result = {"success": True, "output": output, "error": None}
        
        if callback_url:
            background_tasks.add_task(send_callback, callback_url, execution_id, trace_id, result)
            
        return output
    except Exception as e:
        logger.exception(f"Handler error in execution {execution_id}: {e}")
        error_result = {
            "success": False, 
            "output": None, 
            "error": {"code": "HANDLER_ERROR", "message": str(e)}
        }
        if callback_url:
            background_tasks.add_task(send_callback, callback_url, execution_id, trace_id, error_result)
            
        return JSONResponse(status_code=500, content={"error": str(e)})

@app.get("/health")
def health():
    return {"status": "ok"}
