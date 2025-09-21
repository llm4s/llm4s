# Embedx-v2 Rebase Notes

## Overview
This document outlines the rebase conflicts encountered when attempting to update PR #202 (Embedx-v2) against the latest main branch.

## Key Conflicts
The PR introduces extensive changes that conflict with recent refactoring in main:

### 1. Configuration System Changes
- **Main Branch**: Uses `ConfigReader` pattern with dependency injection
- **PR Branch**: Uses direct environment variable access via `EmbeddingConfig` object methods
- **Impact**: Affects `EmbeddingConfig`, `EmbeddingClient`, provider classes, and examples

### 2. Provider Architecture Changes  
- **Main Branch**: Provider classes with constructor injection (`OpenAIEmbeddingProvider(config)`)
- **PR Branch**: Object-based providers (`OpenAIEmbeddingProvider` as singleton objects)
- **Impact**: All provider implementations need updating

### 3. New Features in PR
- **Multimedia Support**: UniversalEncoder, UniversalExtractor for images/audio/video
- **Enhanced CLI**: Rich reporting with ANSI colors, tables, similarity bars
- **Model Selection**: Dynamic model selection based on content/provider
- **Chunking**: Enhanced text chunking with configurable parameters

## Conflicted Files
1. `samples/src/main/scala/org/llm4s/samples/embeddingsupport/EmbeddingExample.scala`
2. `src/main/scala/org/llm4s/llmconnect/EmbeddingClient.scala`
3. `src/main/scala/org/llm4s/llmconnect/config/EmbeddingConfig.scala`
4. `src/main/scala/org/llm4s/llmconnect/provider/OpenAIEmbeddingProvider.scala`
5. `src/main/scala/org/llm4s/llmconnect/provider/VoyageAIEmbeddingProvider.scala`
6. `src/main/scala/org/llm4s/llmconnect/utils/ModelSelector.scala`
7. `build.sbt`

## Recommended Resolution Strategy

### Option 1: Manual Conflict Resolution
1. **Update Configuration**: Adapt PR code to use `ConfigReader` pattern
2. **Update Providers**: Convert to class-based architecture with dependency injection
3. **Update Examples**: Modify to use new configuration system
4. **Test Integration**: Ensure all multimedia features work with new architecture

### Option 2: Feature Branch Strategy  
1. **Preserve PR Features**: Extract the new multimedia functionality
2. **Incremental Integration**: Add features piece by piece on top of current main
3. **Gradual Migration**: Update configuration usage incrementally

## Key Features to Preserve
- **UniversalEncoder/Extractor**: Core multimedia processing capability
- **Enhanced CLI**: Rich reporting and visualization features
- **Model Selection**: Intelligent provider/model selection logic
- **Chunking Improvements**: Enhanced text processing capabilities

## Next Steps
The branch is currently on the original PR state. Maintainers should decide:
1. Whether to manually resolve conflicts and adapt to new architecture
2. Whether to break down the PR into smaller, more manageable pieces
3. Timeline for integration given the substantial architectural changes

## Technical Notes
- All multimedia support is currently stub-based (ready for future real encoders)
- CLI improvements are backward compatible
- New dependencies added: Apache Tika 3.2.1, PDFBox 3.0.5, POI 5.4.1