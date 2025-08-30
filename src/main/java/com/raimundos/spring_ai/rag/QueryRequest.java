package com.raimundos.spring_ai.rag;

public record QueryRequest(String question, Integer topK) {}
