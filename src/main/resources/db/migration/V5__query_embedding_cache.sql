-- Query vectors are cached so re-analysing a claim never depends on the embedding API
-- (the free hosted endpoint answers 503 "overloaded" at times).
CREATE TABLE query_embedding_cache (
    model       varchar(128) NOT NULL,
    query_hash  char(64)     NOT NULL,
    embedding   vector(${embeddingDim}) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (model, query_hash)
);
