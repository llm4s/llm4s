#!/bin/bash

# Load environment variables from .env file
set -a
source .env
set +a

# Run the agent example
sbt "samples/runMain org.llm4s.samples.agent.AgentExample"