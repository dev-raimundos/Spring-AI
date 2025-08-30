package com.raimundos.spring_ai.rag;

import java.util.List;
import java.util.Map;

public record RagAnswer(String answer, List<Source> sources) {
    public record Source(String id, String content, Map<String, Object> metadata) {}
}
