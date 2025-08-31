# Spring AI RAG com Ollama e PostgreSQL + PGVector

Este projeto é uma aplicação Java construída com Spring Boot que demonstra uma arquitetura completa de **RAG (Retrieval-Augmented Generation)** utilizando:

- **Spring AI**
- **Ollama** como provedor local de modelos LLM
- **PostgreSQL com a extensão PGVector** para armazenamento vetorial
- Integração com **modelos de linguagem open-source** executados localmente

---

## Objetivo

Fornecer uma base para projetos que utilizam modelos de linguagem para responder perguntas com base em um repositório vetorial de conhecimento, combinando:

- Recuperação semântica (via embeddings)
- Geração de respostas (via LLMs)

Ideal para aplicações como:

- Assistentes inteligentes
- Chatbots contextuais
- Perguntas e respostas sobre bases documentais

---

##  Tecnologias e Conceitos

- **Spring Boot 3.5+**
- **Spring AI**
- **PostgreSQL 17 com extensão PGVector**
- **Ollama (Local LLM Runtime)**
- **Modelos utilizados:**
    - `gemma3:4b` (LLM para geração)
    - `nomic-embed-text` (modelo de embedding)

---

## Como iniciar o projeto

### 1. Requisitos

- Docker + Docker Compose
- [Ollama instalado no host](https://ollama.com/download)

---

### 2. Baixe os modelos no host

Antes de subir os containers, execute:

```bash
ollama pull gemma3:4b
ollama pull nomic-embed-text
