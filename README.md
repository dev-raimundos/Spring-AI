# API RAG -- Documentação dos Endpoints

Esta API implementa ingestão de documentos com geração de embeddings em
Postgres (pgvector) e consulta RAG com um LLM via Spring AI. Abaixo
estão os endpoints disponíveis, os formatos de requisição e resposta, e
as decisões de design relevantes.

------------------------------------------------------------------------

## Conceitos de base

-   **VectorStore (pgvector):** a API persiste chunks de texto na tabela
    `spring.vector_store`, com `content` (texto), `metadata` (JSONB) e
    `embedding` (vector(768)).
-   **IDs dos documentos:** a implementação atual não define IDs
    manualmente nos `Document`s. O `PgVectorStore` gera um UUID por
    chunk. O identificador humano enviado no payload é preservado em
    `metadata.external_id`.
-   **Chunking:** cada item de ingestão é dividido em pedaços por
    caracteres, com overlap. Cada chunk vira um `Document` separado com
    `metadata.chunk_index`.
-   **Modelos:** embeddings via `nomic-embed-text` (dimensão 768); chat
    via `gemma3:4b` (configurável).
-   **Filtro por metadados:** as consultas podem restringir o escopo com
    `filterExpression`, conforme a DSL de filtros do Spring AI
    (comparações simples e `in`).

------------------------------------------------------------------------

## 1) POST `/rag/ingest`

Ingestão de documentos. Para cada item, o servidor: 1) divide o texto em
chunks (chunking com overlap), 2) gera embeddings via modelo
configurado, 3) persiste os chunks em `pgvector` com metadados.

### Requisição

``` json
{
  "items": [
    {
      "id": "string opcional (identificador humano)",
      "text": "conteúdo integral a ser indexado",
      "metadata": {
        "qualquer_chave": "valor",
        "outra_chave": 123
      }
    }
  ]
}
```

-   `id` (opcional): identificador humano do documento. Não é usado como
    chave primária; é copiado para `metadata.external_id` em cada chunk.
-   `text` (obrigatório): texto bruto que será chunkado e vetorizado.
-   `metadata` (opcional): pares chave/valor simples; evite objetos
    aninhados se pretende filtrar.

### Resposta

``` json
{
  "ingested": 3,
  "ids": [
    "f05a4a7a-1a2b-4f2a-9c74-3a6f0a0a78b1",
    "3d2f9b8e-9f6a-4f53-9e1d-6b7e7f9e4e31",
    "b0e66f1c-42a7-4a48-8c2c-3e084e8f6d45"
  ]
}
```

-   `ingested`: quantidade de chunks gravados.
-   `ids`: UUIDs gerados pelo store para cada chunk.

### Por que funciona assim

-   O `PgVectorStore` (Spring AI 1.0.1) utiliza `UUID` como tipo de ID.
    Ao não enviar ID customizado, evitam-se erros de conversão e
    colisões.
-   Preservar `id` em `metadata.external_id` permite segmentar, depurar
    e filtrar por documento original.

------------------------------------------------------------------------

## 2) POST `/rag/query`

Consulta básica RAG: 1) realiza busca vetorial por similaridade (topK),
2) constrói um prompt com o contexto recuperado, 3) chama o modelo de
chat e retorna a resposta textual e as fontes.

### Requisição

``` json
{
  "question": "sua pergunta",
  "topK": 6
}
```

-   `question` (obrigatório): a pergunta do usuário.
-   `topK` (opcional, padrão 6): quantidade de chunks candidatos.

### Resposta

``` json
{
  "answer": "texto da resposta gerada pelo modelo com base no contexto recuperado",
  "sources": [
    {
      "id": "uuid-do-chunk",
      "content": "trecho do chunk",
      "metadata": {
        "external_id": "seu-id-humano",
        "collection": "manual",
        "chunk_index": 0
      }
    }
  ]
}
```

------------------------------------------------------------------------

## 3) POST `/rag/query/advanced`

Versão avançada da consulta, com limiar de similaridade e filtro por
metadados.

### Requisição

``` json
{
  "question": "sua pergunta",
  "topK": 8,
  "similarityThreshold": 0.5,
  "filterExpression": "collection == 'manual' && external_id == 'linxdms-iis'"
}
```

-   `question` (obrigatório)
-   `topK` (opcional, padrão 6)
-   `similarityThreshold` (opcional, padrão 0.0)
-   `filterExpression` (opcional)

### Resposta

Mesmo formato do `/rag/query`.

------------------------------------------------------------------------

## 4) GET `/rag/query/stream`

Consulta em streaming (Server-Sent Events).

### Parâmetros de query

-   `question` (obrigatório)
-   `topK` (opcional, padrão 6)
-   `similarityThreshold` (opcional, padrão 0.0)
-   `filterExpression` (opcional)

### Resposta

Fluxo de texto SSE com tokens da resposta.

------------------------------------------------------------------------

## Modelos de dados (DTOs)

### `IngestRequest`

``` java
public record IngestRequest(List<IngestItem> items) {}
```

### `IngestItem`

``` java
public record IngestItem(String id, String text, Map<String, Object> metadata) {}
```

### `QueryRequest`

``` java
public record QueryRequest(String question, Integer topK) {}
```

### `QueryAdvancedRequest`

``` java
public record QueryAdvancedRequest(
    String question,
    Integer topK,
    Double similarityThreshold,
    String filterExpression
) {}
```

### `RagAnswer`

``` java
public record RagAnswer(String answer, List<Source> sources) {
    public record Source(String id, String content, Map<String, Object> metadata) {}
}
```

------------------------------------------------------------------------

## Exemplos de uso (curl)

### Ingestão

``` bash
curl -X POST http://localhost:8080/rag/ingest   -H "Content-Type: application/json"   -d '{
    "items": [
      {
        "id": "linxdms-iis",
        "text": "Para executar o Linx DMS Web, é necessário instalar e configurar o IIS...",
        "metadata": { "source":"confluence", "collection":"manual", "system":"LinxDMS", "lang":"pt" }
      }
    ]
  }'
```

### Consulta simples

``` bash
curl -X POST http://localhost:8080/rag/query   -H "Content-Type: application/json"   -d '{"question":"O Linx DMS Web precisa de IIS?","topK":8}'
```

### Consulta avançada

``` bash
curl -X POST http://localhost:8080/rag/query/advanced   -H "Content-Type: application/json"   -d '{
    "question":"Quais passos para instalar o IIS?",
    "topK":8,
    "similarityThreshold":0.5,
    "filterExpression":"collection == '''manual''' && system == '''LinxDMS'''"
  }'
```

### Streaming

``` bash
curl -N "http://localhost:8080/rag/query/stream?question=Como%20configurar%20o%20Application%20Pool&topK=8&similarityThreshold=0.4&filterExpression=collection%20==%20'manual'"
```
