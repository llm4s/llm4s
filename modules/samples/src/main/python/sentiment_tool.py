"""A Python-only function served to llm4s as an MCP tool over stdio.

Stands in for the code a team keeps in Python - a scikit-learn model, a spaCy pipeline, an internal
library - and shows the few lines that make it an agent tool. The scoring itself is deliberately
tiny and pure Python, so the sample needs no ML dependency: only the MCP Python SDK
(`pip install -r requirements.txt` beside this file).

llm4s starts this script as a subprocess (`MCPServerConfig.stdio`) and speaks MCP on its stdin and
stdout. The tool's input schema comes from the type hints and its description from the docstring,
so there is no second contract to keep in step with the Scala side.

Run by `org.llm4s.samples.sidecar.PythonToolBridgeExample`; see
docs/guide/replacing-a-python-sidecar.md.
"""

import re
from typing import TypedDict

from mcp.server.mcpserver import MCPServer

POSITIVE = {"good", "great", "excellent", "love", "happy", "fast", "helpful", "reliable", "easy"}
NEGATIVE = {"bad", "poor", "terrible", "hate", "slow", "broken", "unhelpful", "unreliable", "hard"}


class Sentiment(TypedDict):
    label: str
    score: float
    positive: int
    negative: int


def score(text: str) -> Sentiment:
    """The plain Python function: no MCP in it, so it stays testable and reusable as it is."""
    words = re.findall(r"[a-z']+", text.lower())
    positive = sum(1 for w in words if w in POSITIVE)
    negative = sum(1 for w in words if w in NEGATIVE)
    total = positive + negative
    value = 0.0 if total == 0 else (positive - negative) / total
    label = "positive" if value > 0 else "negative" if value < 0 else "neutral"
    return {"label": label, "score": round(value, 3), "positive": positive, "negative": negative}


server = MCPServer("sentiment")


@server.tool()
def analyze_sentiment(text: str) -> Sentiment:
    """Classify the sentiment of a piece of English text as positive, negative or neutral."""
    return score(text)


if __name__ == "__main__":
    # stdio is the default transport: MCP messages on stdout, so nothing else may print there.
    server.run()
