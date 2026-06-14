from nanofaas.sdk import nanofaas_function, context
from statistics import mean

logger = context.get_logger(__name__)

@nanofaas_function
def handle(input_data):
    logger.info(f"Processing json-transform for execution {context.get_execution_id()}")

    if not isinstance(input_data, dict):
        return {"error": "Input must be a JSON object"}
        
    data = input_data.get("data")
    group_by = input_data.get("groupBy")
    operation = input_data.get("operation", "count")
    value_field = input_data.get("valueField")

    if data is None or group_by is None:
        return {"error": "Fields 'data' and 'groupBy' are required"}

    if operation != "count" and not value_field:
        return {"error": f"Field 'valueField' is required for operation: {operation}"}

    grouped = {}
    for item in data:
        key = str(item.get(group_by, "null"))
        if key not in grouped:
            grouped[key] = []
        grouped[key].append(item)

    result_groups = {}
    for key, items in grouped.items():
        if operation == "count":
            val = len(items)
        else:
            values = [i.get(value_field) for i in items if i.get(value_field) is not None]
            if not values:
                val = 0
            elif operation == "sum":
                val = sum(values)
            elif operation == "avg":
                val = mean(values)
            elif operation == "min":
                val = min(values)
            elif operation == "max":
                val = max(values)
            else:
                val = f"unknown operation: {operation}"
            
        result_groups[key] = val

    return {
        "groupBy": group_by,
        "operation": operation,
        "groups": result_groups
    }
