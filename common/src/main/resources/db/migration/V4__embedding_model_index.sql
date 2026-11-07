create index chunk_embeddings_nemotron_hnsw on chunk_embeddings
    using hnsw ((embedding::halfvec(2048)) halfvec_cosine_ops)
    where model = 'nvidia/nemotron-3-embed-1b:free';