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

package com.google.genkit.plugins.awsbedrock;

import com.google.genkit.core.GenkitException;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;

/**
 * Helper for AWS SigV4-signing AWS Bedrock runtime HTTP requests. Shared by {@link AwsBedrockModel}
 * (Converse API) and {@link AwsBedrockEmbedder} (InvokeModel API).
 */
final class AwsBedrockSigner {

  private AwsBedrockSigner() {}

  /**
   * Returns the {@code bedrock-runtime} host for the configured region.
   *
   * @param options the plugin options
   * @return the host name
   */
  static String runtimeHost(AwsBedrockPluginOptions options) {
    return String.format("bedrock-runtime.%s.amazonaws.com", options.getRegion().id());
  }

  /**
   * Builds a SigV4-signed OkHttp POST request for the AWS Bedrock runtime.
   *
   * @param options the plugin options (region + credentials)
   * @param host the target host (e.g. from {@link #runtimeHost})
   * @param path the request path (e.g. {@code /model/<id>/invoke})
   * @param body the JSON request body
   * @return a signed OkHttp request
   */
  static Request signRequest(
      AwsBedrockPluginOptions options, String host, String path, String body) {
    try {
      AwsCredentials credentials = options.getCredentialsProvider().resolveCredentials();

      java.net.URI uri = java.net.URI.create(String.format("https://%s%s", host, path));

      SdkHttpFullRequest httpRequest =
          SdkHttpFullRequest.builder()
              .uri(uri)
              .method(SdkHttpMethod.POST)
              .putHeader("Content-Type", "application/json")
              .putHeader("Accept", "application/json")
              .contentStreamProvider(
                  () ->
                      new java.io.ByteArrayInputStream(
                          body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              .build();

      Aws4Signer signer = Aws4Signer.create();
      Aws4SignerParams signerParams =
          Aws4SignerParams.builder()
              .awsCredentials(credentials)
              .signingName("bedrock")
              .signingRegion(options.getRegion())
              .build();

      SdkHttpFullRequest signedRequest = signer.sign(httpRequest, signerParams);

      // Post the body as raw bytes with no OkHttp media type. The String RequestBody overload
      // appends "; charset=utf-8" to Content-Type, which the Bedrock InvokeModel API rejects. The
      // signed Content-Type ("application/json") and Accept headers copied below are the sole
      // source
      // of those headers, keeping the request byte-consistent with the SigV4 signature.
      Request.Builder okHttpRequestBuilder =
          new Request.Builder()
              .url(signedRequest.getUri().toURL())
              .post(
                  RequestBody.create(
                      body.getBytes(java.nio.charset.StandardCharsets.UTF_8), (MediaType) null));

      signedRequest
          .headers()
          .forEach(
              (key, values) -> values.forEach(value -> okHttpRequestBuilder.addHeader(key, value)));

      return okHttpRequestBuilder.build();
    } catch (Exception e) {
      throw new GenkitException("Failed to sign AWS request", e);
    }
  }
}
