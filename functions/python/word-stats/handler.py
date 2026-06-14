import re
from collections import Counter
from nanofaas.sdk import nanofaas_function, context

logger = context.get_logger(__name__)

@nanofaas_function
def handle(input_data):
    logger.info(f"Analyzing word stats for execution {context.get_execution_id()}")
    
    if not isinstance(input_data, dict) or "text" not in input_data:
        if isinstance(input_data, str):
            text = input_data
            top_n = 10
        else:
            return {"error": "Field 'text' is required"}
    else:
        text = input_data.get("text")
        top_n = input_data.get("topN", 10)

    if not text:
        return {"error": "Text is empty"}

    # Use regex to find words (alphanumeric)
    words = re.findall(r'\w+', text.lower())
    if not words:
        return {"error": "No words found"}

    counts = Counter(words)
    top_words = [
        {"word": word, "count": count} 
        for word, count in counts.most_common(top_n)
    ]
    
    avg_len = sum(len(w) for w in words) / len(words)

    return {
        "wordCount": len(words),
        "uniqueWords": len(counts),
        "topWords": top_words,
        "averageWordLength": round(avg_len, 2)
    }
