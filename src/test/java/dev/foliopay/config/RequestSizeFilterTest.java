package dev.foliopay.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Review finding M4: a chunked body declares no length, so the header check alone let any size through to the JSON parser. */
class RequestSizeFilterTest {
  private final RequestSizeFilter filter = new RequestSizeFilter();

  /** What a chunked request looks like to the servlet: a body, but no declared length. */
  private static MockHttpServletRequest chunked(byte[] body) {
    MockHttpServletRequest request = undeclared(body);
    request.addHeader("Transfer-Encoding", "chunked");
    return request;
  }

  /** An HTTP/2 stream: no declared length and, by the protocol's rules, no Transfer-Encoding header either. */
  private static MockHttpServletRequest undeclared(byte[] body) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/reconcile") {
          @Override public long getContentLengthLong() { return -1; }
          @Override public int getContentLength() { return -1; }
        };
    request.setContentType("application/json");
    request.setContent(body);
    return request;
  }

  @Test
  void aChunkedBodyOverTheLimitIsRefusedWithoutReachingTheController() throws Exception {
    AtomicReference<HttpServletRequest> reached = new AtomicReference<>();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(chunked(new byte[RequestSizeFilter.MAX_BYTES + 1]), response, (req, res) -> reached.set((HttpServletRequest) req));

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(reached.get()).isNull();
  }

  @Test
  void aChunkedBodyWithinTheLimitReachesTheControllerWhole() throws Exception {
    byte[] body = "{\"mode\":\"clean\"}".getBytes(StandardCharsets.UTF_8);
    AtomicReference<String> seen = new AtomicReference<>();
    AtomicReference<Long> length = new AtomicReference<>();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(chunked(body), response, (req, res) -> {
      try {
        length.set(req.getContentLengthLong());
        seen.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(seen.get()).isEqualTo("{\"mode\":\"clean\"}");
    assertThat(length.get()).isEqualTo(body.length);
  }

  @Test
  void aChunkedBodyCanAlsoBeReadAsText() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    filter.doFilter(chunked("{\"a\":1}".getBytes(StandardCharsets.UTF_8)), new MockHttpServletResponse(), (req, res) -> {
      try {
        seen.set(req.getReader().readLine());
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });
    assertThat(seen.get()).isEqualTo("{\"a\":1}");
  }

  @Test
  void aDeclaredLengthOverTheLimitIsRefusedOnTheHeaderAlone() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reconcile");
    request.setContent(new byte[RequestSizeFilter.MAX_BYTES + 1]);
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> { throw new AssertionError("must not be reached"); });
    assertThat(response.getStatus()).isEqualTo(413);
  }

  /** Fails if only a Transfer-Encoding header triggers the check: over HTTP/2 a body of any size would reach the JSON parser. */
  @Test
  void aBodyWithNoDeclaredLengthAndNoTransferEncodingIsCappedToo() throws Exception {
    AtomicReference<HttpServletRequest> reached = new AtomicReference<>();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(undeclared(new byte[RequestSizeFilter.MAX_BYTES + 1]), response, (req, res) -> reached.set((HttpServletRequest) req));

    assertThat(response.getStatus()).isEqualTo(413);
    assertThat(response.getContentAsString()).contains("BODY_TOO_LARGE");
    assertThat(reached.get()).isNull();
  }

  /** A form post is parsed by Tomcat from its own stream (capped by max-http-form-post-size); buffering it here would hide its parameters. */
  @Test
  void aFormPostIsLeftToTheContainerSoItsParametersStillRead() throws Exception {
    MockHttpServletRequest form = undeclared("a=1".getBytes(StandardCharsets.UTF_8));
    form.setContentType("application/x-www-form-urlencoded");
    form.addParameter("a", "1");
    AtomicReference<String> seen = new AtomicReference<>();

    filter.doFilter(form, new MockHttpServletResponse(), (req, res) -> seen.set(req.getParameter("a")));

    assertThat(seen.get()).isEqualTo("1");
  }

  @Test
  void theBufferedBodyBehavesLikeABlockingServletStream() throws Exception {
    filter.doFilter(undeclared("ab".getBytes(StandardCharsets.UTF_8)), new MockHttpServletResponse(), (req, res) -> {
      try {
        var in = req.getInputStream();
        assertThat(in.isReady()).isTrue();
        assertThat(in.isFinished()).isFalse();
        assertThat(in.read()).isEqualTo('a');
        assertThat(in.read()).isEqualTo('b');
        assertThat(in.isFinished()).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> in.setReadListener(null)).isInstanceOf(UnsupportedOperationException.class);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  /** Fails if multipart is waved through: multipart parsing is off, so it is a body like any other and must be capped here. */
  @Test
  void aMultipartBodyOverTheLimitIsRefusedLikeAnyOther() throws Exception {
    MockHttpServletRequest multipart = undeclared(new byte[RequestSizeFilter.MAX_BYTES + 1]);
    multipart.setContentType("multipart/form-data; boundary=x");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(multipart, response, (req, res) -> { throw new AssertionError("reached"); });

    assertThat(response.getStatus()).isEqualTo(413);
  }
}
