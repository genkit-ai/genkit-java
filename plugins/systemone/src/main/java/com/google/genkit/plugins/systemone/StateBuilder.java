/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.google.genkit.plugins.systemone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.genkit.ai.Document;
import com.google.genkit.ai.Message;
import com.google.genkit.ai.Part;
import com.google.genkit.ai.Role;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the state a decision model evaluates from a request's messages and documents, and collects
 * the media it carries.
 */
final class StateBuilder {

  /** Which kinds of media a model reads besides text and JSON. */
  record Reads(boolean images, boolean audio, boolean video) {
    static final Reads NONE = new Reads(false, false, false);
  }

  /**
   * The media a request carries, as base64 data URLs by kind, each in the order its parts appear,
   * messages first and documents after.
   */
  record Media(List<String> images, List<String> audio, List<String> videos) {
    Media() {
      this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    int size() {
      return images.size() + audio.size() + videos.size();
    }
  }

  /** The state and the media of a request. */
  record Built(Object state, Media media) {}

  /** The turns of a conversation as the state: {role, content} records. */
  static final class Turns extends ArrayList<Map<String, Object>> {}

  /** A state with documents attached: the turns under messages and the documents under context. */
  static final class WithContext extends LinkedHashMap<String, Object> {}

  private final Reads reads;
  private final Media media = new Media();

  private StateBuilder(Reads reads) {
    this.reads = reads;
  }

  /**
   * Gathers the system messages into the text that goes in front of every question's instructions.
   * A system message belongs with the questions, never in the state. Only text can be instructions;
   * loop plumbing left in a history is skipped.
   */
  static String systemPreamble(List<Message> messages) {
    List<String> texts = new ArrayList<>();
    if (messages == null) {
      return "";
    }
    for (Message msg : messages) {
      if (msg.getRole() != Role.SYSTEM || msg.getContent() == null) {
        continue;
      }
      StringBuilder sb = new StringBuilder();
      for (Part part : msg.getContent()) {
        if (isFormatInstructions(part)) {
          continue;
        }
        if (!part.isText()) {
          throw Wire.error(
              "INVALID_ARGUMENT",
              "systemone: a "
                  + partKindName(part)
                  + " part cannot be instructions; a system message is text only");
        }
        sb.append(part.getText());
      }
      String text = sb.toString().strip();
      if (!text.isEmpty()) {
        texts.add(text);
      }
    }
    return String.join("\n\n", texts);
  }

  /**
   * Turns the request into the state and the media: one message as its value, several as {role,
   * content} records, and with documents attached an object of both, so a question can name the
   * context it is about. System messages are instructions, not state, and are left out.
   *
   * <p>Media parts of a kind the model reads become the request's media and contribute nothing to
   * the state; a media part of any other kind is refused. A message of media alone is still a turn,
   * with the empty string as its content.
   */
  static Built build(List<Message> messages, List<Document> docs, boolean stateJson, Reads reads) {
    StateBuilder b = new StateBuilder(reads);
    Turns records = new Turns();
    if (messages != null) {
      for (Message msg : messages) {
        if (msg.getRole() == Role.SYSTEM) {
          continue;
        }
        int collected = b.media.size();
        Object value = b.messageValue(msg.getContent(), stateJson);
        if (value == null) {
          if (b.media.size() == collected) {
            continue;
          }
          value = "";
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("role", msg.getRole() == null ? "" : msg.getRole().getValue());
        record.put("content", value);
        records.add(record);
      }
    }
    if (records.isEmpty()) {
      throw Wire.error(
          "INVALID_ARGUMENT",
          "systemone: the request carries no state; a system message is instructions, so pass a"
              + " prompt or messages too");
    }
    List<Object> context = new ArrayList<>();
    if (docs != null) {
      for (Document doc : docs) {
        Object value = b.documentValue(doc);
        if (value != null) {
          context.add(value);
        }
      }
    }
    if (!context.isEmpty()) {
      WithContext state = new WithContext();
      state.put("messages", records);
      state.put("context", context);
      return new Built(state, b.media);
    }
    if (records.size() == 1) {
      return new Built(records.get(0).get("content"), b.media);
    }
    return new Built(records, b.media);
  }

  /**
   * A message's contribution to the state: the value of its one part, or the values of all of them.
   * A message with nothing to contribute yields null.
   */
  private Object messageValue(List<Part> parts, boolean stateJson) {
    List<Object> values = new ArrayList<>();
    if (parts != null) {
      for (Part part : parts) {
        Object value = partValue(part, stateJson);
        if (value != null) {
          values.add(value);
        }
      }
    }
    return switch (values.size()) {
      case 0 -> null;
      case 1 -> values.get(0);
      default -> values;
    };
  }

  /**
   * A part's contribution to the state. Text is sent as is, or as the JSON it holds when the config
   * asks, with its numbers kept exact; a data part is sent as its value; media is collected and
   * contributes nothing. Loop plumbing is skipped, and any other kind of part is refused.
   */
  private Object partValue(Part part, boolean stateJson) {
    if (isFormatInstructions(part)) {
      return null;
    }
    if (part.isText()) {
      if (stateJson) {
        try {
          return Wire.tree(part.getText());
        } catch (JsonProcessingException e) {
          throw Wire.error(
              "INVALID_ARGUMENT",
              "systemone: stateJSON is set but the text is not JSON: " + e.getOriginalMessage(),
              e);
        }
      }
      return part.getText();
    }
    if (part.isData()) {
      return part.getData();
    }
    if (part.isMedia()) {
      collect(part);
      return null;
    }
    throw Wire.error(
        "INVALID_ARGUMENT",
        "systemone: a "
            + partKindName(part)
            + " part cannot be state; the model takes text, JSON, and media only");
  }

  /** Adds a media part to the request's media, or refuses it when the model does not read it. */
  private void collect(Part part) {
    String[] url = mediaUrl(part);
    String dataUrl = url[0];
    String contentType = url[1];
    boolean read;
    List<String> into;
    if (isKind(contentType, "image/")) {
      read = reads.images();
      into = media.images();
    } else if (isKind(contentType, "audio/")) {
      read = reads.audio();
      into = media.audio();
    } else if (isKind(contentType, "video/")) {
      read = reads.video();
      into = media.videos();
    } else {
      throw Wire.error(
          "INVALID_ARGUMENT",
          "systemone: a "
              + (contentType.isEmpty() ? "media" : contentType)
              + " part is not an image, audio, or video; the models take no other media");
    }
    if (!read) {
      throw Wire.error(
          "INVALID_ARGUMENT", "systemone: the model reads no " + contentType + " media");
    }
    into.add(dataUrl);
  }

  private static boolean isKind(String contentType, String prefix) {
    return contentType.startsWith(prefix) || contentType.startsWith("data:" + prefix);
  }

  /**
   * A document's contribution to the context: its content, read as a message's is, or its content
   * with its metadata when it has any. Text stays text whatever the config says, since a retrieved
   * passage is prose, not a rendered template.
   */
  private Object documentValue(Document doc) {
    Object content = messageValue(doc.getContent(), false);
    if (doc.getMetadata() == null || doc.getMetadata().isEmpty()) {
      return content;
    }
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("content", content);
    value.put("metadata", doc.getMetadata());
    return value;
  }

  /**
   * A media part as the media fields take it: a base64 data URL, and its media type. A base64
   * payload goes out as it is, under the part's media type; any other payload is percent-decoded
   * and encoded. The scheme, the base64 token, and the media type are read without regard to case.
   * A remote URL is refused: the servers take inline media only.
   */
  static String[] mediaUrl(Part part) {
    String text = part.getMedia().getUrl() == null ? "" : part.getMedia().getUrl();
    int colon = text.indexOf(':');
    String scheme = colon < 0 ? text : text.substring(0, colon);
    String rest = colon < 0 ? "" : text.substring(colon + 1);
    switch (scheme.toLowerCase(Locale.ROOT)) {
      case "http", "https", "gs" ->
          throw Wire.error(
              "INVALID_ARGUMENT",
              "systemone: the media \""
                  + text
                  + "\" is a URL, and the models take inline media only; download it first and"
                  + " pass it as a data URL");
      case "data" -> {}
      default ->
          throw Wire.error(
              "INVALID_ARGUMENT", "systemone: a media part must carry its content as a data URL");
    }
    int comma = rest.indexOf(',');
    if (comma < 0) {
      throw Wire.error(
          "INVALID_ARGUMENT", "systemone: reading the media: the data URL has no comma");
    }
    String header = rest.substring(0, comma);
    String payload = rest.substring(comma + 1);
    int semi = header.indexOf(';');
    String mediaType = semi < 0 ? header : header.substring(0, semi);
    String partType = part.getMedia().getContentType();
    String contentType =
        (partType == null || partType.isEmpty() ? mediaType : partType).toLowerCase(Locale.ROOT);
    String prefix = "data:" + contentType + ";base64,";
    if (header.toLowerCase(Locale.ROOT).endsWith(";base64")) {
      return new String[] {prefix + payload, contentType};
    }
    byte[] data = percentDecode(payload);
    return new String[] {prefix + Base64.getEncoder().encodeToString(data), contentType};
  }

  /** Decodes %XX escapes into bytes, leaving every other character as its UTF-8 bytes. */
  private static byte[] percentDecode(String s) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
    for (int i = 0; i < s.length(); ) {
      char c = s.charAt(i);
      if (c == '%') {
        if (i + 2 >= s.length()) {
          throw Wire.error(
              "INVALID_ARGUMENT",
              "systemone: reading the media: invalid URL escape \"" + s.substring(i) + "\"");
        }
        int hi = Character.digit(s.charAt(i + 1), 16);
        int lo = Character.digit(s.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) {
          throw Wire.error(
              "INVALID_ARGUMENT",
              "systemone: reading the media: invalid URL escape \"" + s.substring(i, i + 3) + "\"");
        }
        out.write((hi << 4) | lo);
        i += 3;
      } else {
        int end = i + Character.charCount(s.codePointAt(i));
        byte[] bytes = s.substring(i, end).getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        i = end;
      }
    }
    return out.toByteArray();
  }

  /**
   * Whether the part is the output instructions a generate loop injects for a model without
   * constrained output, which a history recorded from another model's turn keeps.
   */
  static boolean isFormatInstructions(Part part) {
    return part.getMetadata() != null && "output".equals(part.getMetadata().get("purpose"));
  }

  /** Names a part's kind for an error message. */
  static String partKindName(Part part) {
    if (part.isMedia()) {
      return "media";
    }
    if (part.isToolRequest()) {
      return "tool request";
    }
    if (part.isToolResponse()) {
      return "tool response";
    }
    return "custom";
  }
}
