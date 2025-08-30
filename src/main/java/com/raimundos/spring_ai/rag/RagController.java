package com.raimundos.spring_ai.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/rag")
public class RagController {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagController(VectorStore vectorStore, ChatClient chatClient) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
    }

    /**
     * Ingestão de documentos:
     * - Gera embedding com o modelo configurado em spring.ai.ollama.embedding.options.model
     * - Persiste no Postgres (pgvector)
     */
    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        java.util.List<org.springframework.ai.document.Document> docs = new ArrayList<>();

        for (IngestItem item : request.items()) {
            Map<String, Object> meta = Optional.ofNullable(item.metadata()).orElseGet(HashMap::new);
            String id = item.id();

            // Construtores válidos em 1.0.1:
            // new Document(String text, Map<String,Object> metadata)
            // new Document(String id, String text, Map<String,Object> metadata)
            org.springframework.ai.document.Document d = (id == null || id.isBlank())
                    ? new org.springframework.ai.document.Document(item.text(), meta)
                    : new org.springframework.ai.document.Document(id, item.text(), meta);

            docs.add(d);
        }

        vectorStore.add(docs); // gera embeddings e salva

        return Map.of(
                "ingested", docs.size(),
                "ids", docs.stream().map(org.springframework.ai.document.Document::getId).toList()
        );
    }

    /**
     * Consulta RAG:
     * - Faz similarity search (topK)
     * - Monta um prompt com os trechos recuperados como contexto
     * - Chama o modelo de chat configurado (spring.ai.ollama.chat.options.model)
     */
    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RagAnswer query(@RequestBody QueryRequest request) {
        int topK = (request.topK() != null && request.topK() > 0) ? request.topK() : 4;

        SearchRequest sr = SearchRequest.builder()
                .query(request.question())
                .topK(topK)
                .build();

        java.util.List<org.springframework.ai.document.Document> results = vectorStore.similaritySearch(sr);

        // Constrói um contexto curto com fontes
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            var d = results.get(i);
            context.append("\n[DOC ").append(i + 1).append("]\n")
                    .append(d.getText()).append("\n");
            if (d.getMetadata() != null && !d.getMetadata().isEmpty()) {
                context.append("(metadata: ").append(d.getMetadata()).append(")\n");
            }
        }

        String system = """
                Você é um assistente que responde APENAS com base no CONTEXTO fornecido.
                Se a resposta não estiver no contexto, diga claramente que não encontrou.
                Seja conciso e cite os [DOC n] relevantes quando possível.
                """;

        String user = """
                PERGUNTA:
                %s

                CONTEXTO:
                %s
                """.formatted(request.question(), context.toString());

        String answer = chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .content();

        return new RagAnswer(
                answer,
                results.stream()
                        .map(doc -> new RagAnswer.Source(doc.getId(), doc.getText(), doc.getMetadata()))
                        .toList()
        );
    }
}
