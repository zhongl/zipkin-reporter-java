/**
 * Copyright 2016 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.reporter.urlconnection;

import com.google.auto.value.AutoValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.zip.GZIPOutputStream;
import zipkin.Codec;
import zipkin.Span;
import zipkin.reporter.Reporter;

/**
 * Reports spans to Zipkin, using its {@code POST /spans} endpoint.
 */
@AutoValue
public abstract class URLConnectionReporter implements Reporter {

  /**
   * Returns the POST URL for zipkin's <a href="http://zipkin.io/zipkin-api/#/">v1 api</a>, usually
   * "http://zipkinhost:9411/api/v1/spans".
   *
   * <p>This is exposed for those who wish to set this dynamically.
   */
  // @FunctionalInterface except library compatible w/ JRE 7
  public interface PostURL {
    /** This will be called for each POST request. Do not return null. */
    String get();
  }

  @AutoValue // for pretty toString
  static abstract class HardCodedPostURL implements PostURL {
  }

  public static Builder builder() {
    return new AutoValue_URLConnectionReporter.Builder()
        .connectTimeout(10 * 1000)
        .readTimeout(60 * 1000)
        .compressionEnabled(true)
        .executor(Runnable::run);
  }

  @AutoValue.Builder
  public static abstract class Builder {
    /** @see #postUrl(PostURL) */
    public abstract Builder postUrl(PostURL postUrl);

    /**
     * No default. URL to POST json-encoded spans to. Ex http://zipkinhost:9411/api/v1/spans
     *
     * @see PostURL
     */
    public final Builder postUrl(String postUrl) {
      return postUrl(new AutoValue_URLConnectionReporter_HardCodedPostURL(postUrl));
    }

    /** Default 10 * 1000 milliseconds. 0 implies no timeout. */
    public abstract Builder connectTimeout(int connectTimeout);

    /** Default 60 * 1000 milliseconds. 0 implies no timeout. */
    public abstract Builder readTimeout(int readTimeout);

    /** Default true. true implies that spans will be gzipped before transport. */
    public abstract Builder compressionEnabled(boolean compressSpans);

    /** Default calling thread. The executor used to defer http requests. */
    public abstract Builder executor(Executor executor);

    public abstract URLConnectionReporter build();

    Builder() {
    }
  }

  public Builder toBuilder() {
    return new AutoValue_URLConnectionReporter.Builder(this);
  }

  abstract PostURL postUrl();

  abstract int connectTimeout();

  abstract int readTimeout();

  abstract boolean compressionEnabled();

  abstract Executor executor();

  /** Asynchronously sends the spans as a json POST to {@link #postUrl()}. */
  @Override public void report(List<Span> spans, Callback callback) {
    executor().execute(() -> {
      try {
        byte[] body = Codec.JSON.writeSpans(spans);
        send(body, "application/json");
        callback.onComplete();
      } catch (RuntimeException | IOException | Error e) {
        callback.onError(e);
        if (e instanceof Error) throw (Error) e;
      }
    });
  }

  void send(byte[] body, String mediaType) throws IOException {
    URL postUrl = new URL(postUrl().get());
    // intentionally not closing the connection, so as to use keep-alives
    HttpURLConnection connection = (HttpURLConnection) postUrl.openConnection();
    connection.setConnectTimeout(connectTimeout());
    connection.setReadTimeout(readTimeout());
    connection.setRequestMethod("POST");
    connection.addRequestProperty("Content-Type", mediaType);
    if (compressionEnabled()) {
      connection.addRequestProperty("Content-Encoding", "gzip");
      ByteArrayOutputStream gzipped = new ByteArrayOutputStream();
      try (GZIPOutputStream compressor = new GZIPOutputStream(gzipped)) {
        compressor.write(body);
      }
      body = gzipped.toByteArray();
    }
    connection.setDoOutput(true);
    connection.setFixedLengthStreamingMode(body.length);
    connection.getOutputStream().write(body);

    try (InputStream in = connection.getInputStream()) {
      while (in.read() != -1) ; // skip
    } catch (IOException e) {
      try (InputStream err = connection.getErrorStream()) {
        if (err != null) { // possible, if the connection was dropped
          while (err.read() != -1) ; // skip
        }
      }
      throw e;
    }
  }

  URLConnectionReporter() {
  }
}