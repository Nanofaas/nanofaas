import json
import io
import base64
import threading

import torch
from PIL import Image
from torchvision import models, transforms
from torchvision.models import ResNet50_Weights
import requests
from nanofaas.sdk import nanofaas_function, context

logger = context.get_logger(__name__)

_init_lock = threading.Lock()


def init():
    """Returns the ResNet50 pre-trained model and output labels."""
    if not hasattr(init, "model"):
        with _init_lock:
            if not hasattr(init, "model"):
                model = models.resnet50(weights=ResNet50_Weights.IMAGENET1K_V1)
                model.eval()

                classes_url = "https://raw.githubusercontent.com/pytorch/hub/master/imagenet_classes.txt"
                labels = requests.get(classes_url).text.strip().split("\n")

                init.model = model
                init.labels = labels

    return init.model, init.labels


def classify(pillow_image, threshold=0.1):
    model, labels = init()

    # Load and preprocess image. Requires since ResNet50 is trained on ImageNet
    # and the given image must be adapted to the expected input format as in
    # ImageNet.
    preprocess = transforms.Compose(
        [
            transforms.Resize(256),
            transforms.CenterCrop(224),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ]
    )
    input_tensor = preprocess(pillow_image)  # Shape [3, 224, 224].

    # Required even if we have only one image (shape [1, 3, 224, 224]).
    input_batch = input_tensor.unsqueeze(0)

    with torch.no_grad():
        output = model(input_batch)

    # Apply softmax to get probabilities.
    probabilities = torch.nn.functional.softmax(output[0], dim=0)

    # Get top 3 predictions above threshold.
    predictions = []
    for i in torch.argsort(probabilities, descending=True):
        prob = probabilities[i].item()
        if prob >= threshold:
            predictions.append({"class": labels[i], "probability": prob})

        if len(predictions) >= 3:  # Only take top 3
            break

    return predictions


@nanofaas_function
def handle(input_data):
    """Function executed by NanoFaaS when a request is incoming."""
    logger.info(f"mlimage invoked, executionId={context.get_execution_id()}")

    try:
        if type(input_data) != str:
            return {"statusCode": 400, "body": "Input payload must be an image encoded as base64 as JSON string"}

        # The image is encoded as base64 with a data URI prefix. The prefix must
        # be removed.
        if "," in input_data:  # Example: "data:image/png;base64,..."
            input_data = input_data.split(",", 1)[1]

        # Decode Base64 image.
        image_bytes = base64.b64decode(input_data, validate=True)

        input_image = Image.open(io.BytesIO(image_bytes))
    except Exception as e:
        logger.error(f"Failed to load image: {e}")
        return {"statusCode": 400, "body": "Bad image."}

    results = classify(input_image)

    return {"statusCode": 200, "body": json.dumps(results)}
