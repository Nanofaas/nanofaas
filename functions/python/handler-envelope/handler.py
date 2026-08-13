from nanofaas.sdk import context, nanofaas_function


@nanofaas_function
def handle(input_data):
    input_data = input_data if isinstance(input_data, dict) else {}
    return {
        "body": input_data.get("message", ""),
        "header": context.get_headers().get("x-e2e-token", ""),
    }
