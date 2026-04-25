# Reliability Notes

Retrieval-Augmented Generation (RAG) reduces hallucination risk by retrieving relevant context at query time.

A practical modular pipeline includes:

1. Ingestion module: Extract and chunk input documents (TXT, MD, PDF, DOCX).
2. Retrieval module: Embed query and retrieve top-k chunks by semantic relevance.
3. Generation module: Build grounded prompt from retrieved chunks and ask the LLM to answer.

This decomposition keeps responsibilities clear and makes each stage independently testable.
