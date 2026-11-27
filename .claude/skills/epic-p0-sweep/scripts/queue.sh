#!/bin/bash
# usage: bash queue.sh   prints main's merge queue, one entry per line
gh api graphql -f query='{repository(owner:"llm4s",name:"llm4s"){mergeQueue(branch:"main"){entries(first:20){nodes{position state pullRequest{number}}}}}}' \
  -q '.data.repository.mergeQueue.entries.nodes[]|"q\(.position) \(.state) #\(.pullRequest.number)"'
