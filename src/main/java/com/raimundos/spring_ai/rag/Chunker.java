package com.raimundos.spring_ai.rag;

import org.springframework.ai.document.Document;

import java.util.*;

public final class Chunker {

    private Chunker() {
    }

    // Chunk por caracteres com overlap — simples e confiável para qualquer versão.
    private static final int CHUNK_SIZE = 2000; // ~aprox. 700-900 tokens
    private static final int OVERLAP = 300;

    public static List<Document> toChunks(String baseId, String text, Map<String, Object> metadata) {
        if (text == null || text.isBlank()) return List.of();

        final int CHUNK_SIZE = 2000, OVERLAP = 300;
        List<Document> docs = new ArrayList<>();
        int n = text.length(), start = 0, idx = 0;

        while (start < n) {
            int end = Math.min(start + CHUNK_SIZE, n);
            String slice = text.substring(start, end);

            Map<String,Object> meta = new HashMap<>(Optional.ofNullable(metadata).orElseGet(HashMap::new));
            meta.put("chunk_index", idx);
            if (baseId != null && !baseId.isBlank()) {
                meta.put("external_id", baseId); // <- guarda seu id aqui
            }

            // NÃO passe id no Document -> PgVectorStore gera UUID
            docs.add(new Document(slice, meta));

            if (end == n) break;
            start = Math.max(end - OVERLAP, start + 1);
            idx++;
        }
        return docs;
    }
}