package dev.foliopay.gateway.http;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

final class HttpClients {
  private HttpClients() {}

  /** Every downstream call has a connect and a read timeout: without them one slow system holds a request thread for ever. */
  static SimpleClientHttpRequestFactory factory(HttpGatewayProperties settings) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) settings.connectTimeout().toMillis());
    factory.setReadTimeout((int) settings.readTimeout().toMillis());
    return factory;
  }
}
