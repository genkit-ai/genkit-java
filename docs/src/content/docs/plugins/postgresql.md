---
title: PostgreSQL (pgvector)
description: Vector similarity search with PostgreSQL and pgvector extension.
---

## Installation

```xml
<dependency>
    <groupId>com.google.genkit</groupId>
    <artifactId>genkit-plugin-postgresql</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Requirements

- PostgreSQL 12+ with the [pgvector](https://github.com/pgvector/pgvector) extension installed
- Java 21+

## Usage

```java
import com.google.genkit.plugins.postgresql.PostgreSQLPlugin;

Genkit genkit = Genkit.builder()
    .plugin(PostgreSQLPlugin.create(
        "jdbc:postgresql://localhost:5432/mydb",
        "user", "password"))
    .build();
```

## Features

- Cosine, L2, and inner product similarity search
- Automatic schema management
- Connection pooling
- Batch indexing
- Metadata support

## Session store

The plugin also ships `PostgresSessionStore`, a PostgreSQL-backed agent session store. Construct it from a `javax.sql.DataSource` and pass it to an agent's `.store(...)` to persist server-managed sessions in PostgreSQL:

```java
import com.google.genkit.plugins.postgresql.session.PostgresSessionStore;
import com.google.genkit.plugins.postgresql.session.PostgresSessionStoreOptions;

PostgresSessionStore<Map<String, Object>> store =
    new PostgresSessionStore<>(
        dataSource, PostgresSessionStoreOptions.builder().createTableIfNotExists(true).build());
```

See [Session Stores](../../agents/session-stores#postgressessionstore) for options and the agents-postgres-session sample.

## Sample

See the [postgresql sample](https://github.com/genkit-ai/genkit-java/tree/main/samples/postgresql).
