package com.healthclaim.document.service;

import com.healthclaim.document.chunker.Chunk;
import com.healthclaim.document.chunker.DocumentChunker;
import com.healthclaim.document.chunker.TextCleaner;
import com.healthclaim.document.parser.ExtractedDocument;
import com.healthclaim.document.parser.PageText;
import com.healthclaim.document.parser.PdfTextExtractor;
import com.healthclaim.document.storage.DocumentStorageService;
import com.healthclaim.rag.embedding.EmbeddingProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates ingestion of a single document: retrieve bytes, extract text,
 * clean, chunk, embed, and persist. Parsing/chunking/embedding run outside any
 * DB transaction; only short read/write steps are transactional (see
 * {@link IngestionStore}).
 */
@Service
public class DocumentIngestionService {

    private final IngestionStore store;
    private final DocumentStorageService storage;
    private final PdfTextExtractor extractor;
    private final TextCleaner cleaner;
    private final DocumentChunker chunker;
    private final EmbeddingProvider embeddingProvider;

    public DocumentIngestionService(IngestionStore store,
                                    DocumentStorageService storage,
                                    PdfTextExtractor extractor,
                                    TextCleaner cleaner,
                                    DocumentChunker chunker,
                                    EmbeddingProvider embeddingProvider) {
        this.store = store;
        this.storage = storage;
        this.extractor = extractor;
        this.cleaner = cleaner;
        this.chunker = chunker;
        this.embeddingProvider = embeddingProvider;
    }

    /** Parses one uploaded document, embeds its chunks, and (re)builds them. */
    public int ingestDocument(UUID documentId) {
        IngestRef ref = store.loadRef(documentId);
        byte[] bytes = storage.retrieve(ref.storageKey());
        ExtractedDocument extracted = extractor.extract(bytes);
        ExtractedDocument cleaned = new ExtractedDocument(
                extracted.pageCount(),
                extracted.pages().stream()
                        .map(page -> new PageText(page.pageNumber(), cleaner.clean(page.text())))
                        .toList());
        List<Chunk> chunks = chunker.chunk(cleaned);
        List<float[]> embeddings = chunks.isEmpty()
                ? List.of()
                : embeddingProvider.embedPassages(chunks.stream().map(Chunk::text).toList());
        return store.replaceChunks(documentId, ref.policyId(), extracted.pageCount(),
                chunks, embeddings, embeddingProvider.modelName());
    }
}
