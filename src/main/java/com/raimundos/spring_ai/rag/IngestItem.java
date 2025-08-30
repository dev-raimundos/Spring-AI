package com.raimundos.spring_ai.rag;

import java.util.Map;

public record IngestItem(String id, String text, Map<String, Object> metadata) {}
