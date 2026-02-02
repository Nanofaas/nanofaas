import sys
import os

# Add src to path for imports
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'src'))
# Add tests to path for fixtures
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

# Set handler config before importing app
os.environ['HANDLER_MODULE'] = 'fixtures.handler'
os.environ['HANDLER_FUNCTION'] = 'handle'
os.environ['CALLBACK_URL'] = ''  # Disable callbacks in tests

import pytest
from nanofaas_runtime.app import app


@pytest.fixture
def client():
    """Create test client"""
    app.config['TESTING'] = True
    with app.test_client() as client:
        yield client
