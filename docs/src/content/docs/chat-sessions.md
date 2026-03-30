---
title: Chat Sessions
description: Build multi-turn chat experiences with session persistence.
---

Genkit supports multi-turn chat sessions with automatic history management, typed session state, multiple conversation threads, and pluggable persistence via `SessionStore`.

## Creating a session

```java
Session<Void> session = genkit.createSession();
```

### With session options

```java
import com.google.genkit.ai.session.*;

Session<ConversationState> session = genkit.createSession(
    SessionOptions.<ConversationState>builder()
        .store(sessionStore)
        .initialState(new ConversationState("John"))
        .sessionId("custom-session-id")  // optional, auto-generated if omitted
        .build());
```

## Starting a chat

Create a `Chat` from a session. The chat manages message history, tool execution, and streaming automatically:

```java
Chat<ConversationState> chat = session.chat(
    ChatOptions.<ConversationState>builder()
        .model("openai/gpt-4o-mini")
        .system("You are a helpful assistant.")
        .tools(List.of(noteTool, searchTool))
        .config(GenerationConfig.builder().temperature(0.7).build())
        .build());
```

## Sending messages

Each `send()` call automatically includes the full conversation history:

```java
ModelResponse r1 = chat.send("What is the capital of France?");
System.out.println(r1.getText()); // "Paris"

ModelResponse r2 = chat.send("And what about Germany?");
System.out.println(r2.getText()); // "Berlin" — knows we're asking about capitals
```

### With per-message options

Override model or tools for a specific message:

```java
ModelResponse response = chat.send("Summarize everything",
    Chat.SendOptions.builder()
        .model("openai/gpt-4o")  // use a different model for this turn
        .maxTurns(5)
        .build());
```

### Streaming

```java
ModelResponse response = chat.sendStream("Tell me a long story",
    (chunk) -> System.out.print(chunk.getText()));
```

## Session state

Sessions can hold typed state that persists across messages and is saved to the store:

```java
public class ConversationState {
    private String userName;
    private int messageCount;
    private List<String> topics;
    // constructors, getters, setters...
}

// Read state
ConversationState state = session.getState();
System.out.println("User: " + state.getUserName());

// Update state
state.incrementMessageCount();
state.getTopics().add("geography");
session.updateState(state).join();
```

## Conversation threads

A single session can have multiple independent conversation threads, each with its own history:

```java
Chat<ConversationState> generalChat = session.chat("general",
    ChatOptions.<ConversationState>builder()
        .model("openai/gpt-4o-mini")
        .system("You are a general assistant.")
        .build());

Chat<ConversationState> supportChat = session.chat("support",
    ChatOptions.<ConversationState>builder()
        .model("openai/gpt-4o-mini")
        .system("You are a support agent.")
        .build());

// Each thread maintains separate history
generalChat.send("What's the weather like?");
supportChat.send("I have a billing issue");
```

### Accessing history

```java
List<Message> history = chat.getHistory();
List<Message> supportHistory = session.getMessages("support");
List<Message> mainHistory = session.getMessages(); // default "main" thread
```

## Loading existing sessions

Resume a previous session by loading it from the store:

```java
Session<ConversationState> loaded = genkit.loadSession(
    "session-123",
    SessionOptions.<ConversationState>builder()
        .store(sessionStore)
        .build()
).get();  // returns CompletableFuture

// Continue the conversation with full history
Chat<ConversationState> chat = loaded.chat();
chat.send("Where were we?");
```

## SessionStore

The `SessionStore` interface defines how sessions are persisted. Genkit includes an in-memory implementation, and you can create custom stores for any backend.

### Interface

```java
public interface SessionStore<S> {
    CompletableFuture<SessionData<S>> get(String sessionId);
    CompletableFuture<Void> save(String sessionId, SessionData<S> sessionData);
    CompletableFuture<Void> delete(String sessionId);
}
```

### InMemorySessionStore

The default implementation stores sessions in a `ConcurrentHashMap`. Useful for development and testing — data is lost when the process stops:

```java
InMemorySessionStore<ConversationState> store = new InMemorySessionStore<>();

Session<ConversationState> session = genkit.createSession(
    SessionOptions.<ConversationState>builder()
        .store(store)
        .initialState(new ConversationState("John"))
        .build());
```

### Creating a custom SessionStore

Implement the `SessionStore` interface to persist sessions to any backend — Redis, a database, Firebase, etc.

```java
import com.google.genkit.ai.session.SessionStore;
import com.google.genkit.ai.session.SessionData;

public class RedisSessionStore<S> implements SessionStore<S> {
    private final RedisClient redis;
    private final ObjectMapper mapper;

    public RedisSessionStore(RedisClient redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public CompletableFuture<SessionData<S>> get(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            String json = redis.get("session:" + sessionId);
            if (json == null) return null;
            return mapper.readValue(json, SessionData.class);
        });
    }

    @Override
    public CompletableFuture<Void> save(String sessionId, SessionData<S> data) {
        return CompletableFuture.runAsync(() -> {
            String json = mapper.writeValueAsString(data);
            redis.set("session:" + sessionId, json);
        });
    }

    @Override
    public CompletableFuture<Void> delete(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            redis.del("session:" + sessionId);
        });
    }
}
```

Then use it like any other store:

```java
RedisSessionStore<ConversationState> store =
    new RedisSessionStore<>(redisClient, objectMapper);

Session<ConversationState> session = genkit.createSession(
    SessionOptions.<ConversationState>builder()
        .store(store)
        .initialState(new ConversationState("John"))
        .build());
```

### SessionData structure

The `SessionData` object is what gets persisted. It contains:

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | Session ID |
| `state` | `S` | Your typed session state |
| `threads` | `Map<String, List<Message>>` | All conversation threads and their message histories |

```java
// Access thread data directly
SessionData<ConversationState> data = store.get("session-123").get();
List<Message> mainThread = data.getThread("main");
Map<String, List<Message>> allThreads = data.getThreads();
```

## Sample

See the [chat-session sample](https://github.com/xavidop/genkit-java/tree/main/samples/chat-session) for a complete multi-turn chat implementation with state, tools, and session persistence.
