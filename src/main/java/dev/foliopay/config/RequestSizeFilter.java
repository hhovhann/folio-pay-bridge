package dev.foliopay.config;

import dev.foliopay.api.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized request bodies before they are parsed (OWASP A04): the largest legitimate body is a pasted settlement statement. A body
 * that declares its length is refused on the header. A body that declares none (chunked over HTTP/1.1, or any HTTP/2 stream, which has no
 * Transfer-Encoding header) is read up to the limit and refused if it goes past. URL-encoded form posts are left to Tomcat, which parses
 * them itself and caps them with {@code server.tomcat.max-http-form-post-size}. Multipart parsing is switched off
 * ({@code spring.servlet.multipart.enabled=false}): this API takes JSON only, so a multipart body is just a body and is capped here.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1) // after Spring Security: an unauthenticated caller cannot make us buffer a megabyte
class RequestSizeFilter extends OncePerRequestFilter {
  static final int MAX_BYTES = 1_000_000;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    long declared = request.getContentLengthLong();
    if (declared > MAX_BYTES) {
      tooLarge(response);
      return;
    }
    if (declared < 0 && !isForm(request)) {
      byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
      if (body.length > MAX_BYTES) {
        tooLarge(response);
        return;
      }
      chain.doFilter(new BufferedBody(request, body), response);
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean isForm(HttpServletRequest request) {
    String type = request.getContentType();
    return type != null && type.startsWith("application/x-www-form-urlencoded");
  }

  private static void tooLarge(HttpServletResponse response) throws IOException {
    JsonErrors.write(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, ErrorCode.BODY_TOO_LARGE, "Request body too large");
  }

  /** A request whose body has already been read, within the limit, and is served again from memory. */
  private static final class BufferedBody extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedBody(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override public int getContentLength() { return body.length; }
    @Override public long getContentLengthLong() { return body.length; }

    @Override
    public BufferedReader getReader() {
      Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8 : Charset.forName(getCharacterEncoding());
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override public int read() { return in.read(); }
        @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
        @Override public boolean isFinished() { return in.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("blocking only"); }
      };
    }
  }
}
