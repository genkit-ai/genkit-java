/*
 * Copyright 2025 Google LLC
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

package com.google.genkit.plugins.firebase.functions;

import java.util.HashMap;
import java.util.Map;

/**
 * Authentication context for Firebase Cloud Functions.
 * 
 * <p>
 * Contains information about the authenticated user, including their Firebase
 * Auth token and any custom claims.
 */
public class AuthContext {

  private String token;
  private String uid;
  private String email;
  private boolean emailVerified;
  private Map<String, Object> claims;

  /**
   * Creates an empty auth context.
   */
  public AuthContext() {
    this.claims = new HashMap<>();
  }

  /**
   * Creates an auth context with the given token.
   *
   * @param token
   *            the Firebase Auth token
   */
  public AuthContext(String token) {
    this();
    this.token = token;
  }

  public String getToken() {
    return token;
  }

  public void setToken(String token) {
    this.token = token;
  }

  public String getUid() {
    return uid;
  }

  public void setUid(String uid) {
    this.uid = uid;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }

  public void setEmailVerified(boolean emailVerified) {
    this.emailVerified = emailVerified;
  }

  public Map<String, Object> getClaims() {
    return claims;
  }

  public void setClaims(Map<String, Object> claims) {
    this.claims = claims != null ? claims : new HashMap<>();
  }

  /**
   * Gets a specific claim value.
   *
   * @param name
   *            the claim name
   * @return the claim value, or null if not present
   */
  public Object getClaim(String name) {
    return claims.get(name);
  }

  /**
   * Checks if a claim exists and is truthy.
   *
   * @param name
   *            the claim name
   * @return true if the claim exists and is truthy
   */
  public boolean hasClaim(String name) {
    Object value = claims.get(name);
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    return true;
  }

  /**
   * Builder for AuthContext.
   */
  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final AuthContext context = new AuthContext();

    public Builder token(String token) {
      context.setToken(token);
      return this;
    }

    public Builder uid(String uid) {
      context.setUid(uid);
      return this;
    }

    public Builder email(String email) {
      context.setEmail(email);
      return this;
    }

    public Builder emailVerified(boolean emailVerified) {
      context.setEmailVerified(emailVerified);
      return this;
    }

    public Builder claims(Map<String, Object> claims) {
      context.setClaims(claims);
      return this;
    }

    public Builder claim(String name, Object value) {
      context.getClaims().put(name, value);
      return this;
    }

    public AuthContext build() {
      return context;
    }
  }
}
