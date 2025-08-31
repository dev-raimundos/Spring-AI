package com.raimundos.spring_ai.rag;

public record QueryAdvancedRequest(
        String question,
        Integer topK,
        Double similarityThreshold,
        String filterExpression
) {}
