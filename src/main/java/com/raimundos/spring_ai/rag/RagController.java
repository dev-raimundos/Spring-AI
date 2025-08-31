package com.raimundos.spring_ai.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/rag")
public class RagController {

    private static final int DEFAULT_TOPK = 6;
    private static final double DEFAULT_SIM_THRESHOLD = 0.0; // ajuste no advanced
    private static final int MAX_CONTEXT_CHARS = 10_000;     // segurança do prompt

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagController(VectorStore vectorStore, ChatClient chatClient) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
    }

    /* =========================
       INGESTÃO (com CHUNKING)
       ========================= */
    @PostMapping(value = "/ingest", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            return Map.of("ingested", 0, "ids", List.of());
        }

        List<Document> toAdd = new ArrayList<>();
        for (IngestItem item : request.items()) {
            Map<String,Object> meta = Optional.ofNullable(item.metadata()).orElseGet(HashMap::new);
            List<Document> chunks = Chunker.toChunks(item.id(), item.text(), meta);
            toAdd.addAll(chunks);
        }
        vectorStore.add(toAdd);

        List<String> ids = toAdd.stream().map(Document::getId).toList();
        return Map.of("ingested", toAdd.size(), "ids", ids);
    }

    /* =========================
       CONSULTA RÁPIDA (compat)
       ========================= */
    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RagAnswer query(@RequestBody QueryRequest request) {
        int topK = (request.topK() != null && request.topK() > 0) ? request.topK() : DEFAULT_TOPK;

        SearchRequest sr = SearchRequest.builder()
                .query(request.question())
                .topK(topK)
                .build();

        List<Document> results = vectorStore.similaritySearch(sr);

        String prompt = buildPrompt(request.question(), results);
        String answer = chatClient
                .prompt()
                .system(systemPrompt())
                .user(prompt)
                .call()
                .content();

        return toRagAnswer(answer, results);
    }

    /* ===============================================
       CONSULTA AVANÇADA (filtro + threshold + topK)
       =============================================== */
    @PostMapping(value = "/query/advanced", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public RagAnswer queryAdvanced(@RequestBody QueryAdvancedRequest request) {
        int topK = (request.topK() != null && request.topK() > 0) ? request.topK() : DEFAULT_TOPK;
        double threshold = request.similarityThreshold() != null ? request.similarityThreshold() : DEFAULT_SIM_THRESHOLD;

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(request.question())
                .topK(topK)
                .similarityThreshold(threshold);

        if (StringUtils.hasText(request.filterExpression())) {
            builder.filterExpression(request.filterExpression());
        }

        List<Document> results = vectorStore.similaritySearch(builder.build());

        String prompt = buildPrompt(request.question(), results);
        String answer = chatClient
                .prompt()
                .system(systemPrompt())
                .user(prompt)
                .call()
                .content();

        return toRagAnswer(answer, results);
    }

    /* ===============================================
       STREAMING (SSE) — sem MMR
       =============================================== */
    @GetMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryStream(@RequestParam String question,
                                    @RequestParam(required = false) Integer topK,
                                    @RequestParam(required = false) String filterExpression,
                                    @RequestParam(required = false) Double similarityThreshold) {

        int k = (topK != null && topK > 0) ? topK : DEFAULT_TOPK;
        double threshold = (similarityThreshold != null) ? similarityThreshold : DEFAULT_SIM_THRESHOLD;

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(question)
                .topK(k)
                .similarityThreshold(threshold);

        if (StringUtils.hasText(filterExpression)) {
            builder.filterExpression(filterExpression);
        }

        List<Document> results = vectorStore.similaritySearch(builder.build());
        String prompt = buildPrompt(question, results);

        return chatClient
                .prompt()
                .system(systemPrompt())
                .user(prompt)
                .stream()
                .content();
    }

    /* =========================
       HELPERS
       ========================= */
    private String systemPrompt() {
        return """
                Você é um assistente que responde APENAS com base no CONTEXTO fornecido.
                - Se a resposta não estiver no contexto, diga claramente que não encontrou.
                - Seja conciso (6–8 linhas) e use linguagem direta.
                - Quando usar um trecho, cite [DOC n].
                - Não invente URLs nem números.
                """;
    }

    private String buildPrompt(String question, List<Document> results) {
        String ctx = stringifyResults(results);
        return """
                PERGUNTA:
                %s

                CONTEXTO:
                %s
                """.formatted(question, ctx);
    }

    private String stringifyResults(List<Document> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            Document d = results.get(i);
            sb.append("\n[DOC ").append(i + 1).append("]\n")
                    .append(truncate(d.getText(), 1800)).append("\n");

            if (d.getMetadata() != null && !d.getMetadata().isEmpty()) {
                String md = d.getMetadata().entrySet().stream()
                        .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()))
                        .collect(Collectors.joining(", "));
                if (!md.isEmpty()) sb.append("(metadata: ").append(md).append(")\n");
            }
        }
        if (sb.length() > MAX_CONTEXT_CHARS) {
            return sb.substring(0, MAX_CONTEXT_CHARS) + "\n[...contexto truncado...]";
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + " …";
    }

    private RagAnswer toRagAnswer(String answer, List<Document> results) {
        List<RagAnswer.Source> sources = new ArrayList<>(results.size());
        for (Document d : results) {
            sources.add(new RagAnswer.Source(d.getId(), d.getText(), d.getMetadata()));
        }
        return new RagAnswer(answer, sources);
    }
}
