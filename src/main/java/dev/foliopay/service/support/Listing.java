package dev.foliopay.service.support;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** How the API's lists are bounded: the newest {@value #NEWEST}, as docs/openapi.json promises. A list that needs more gets a cursor. */
public final class Listing {
  public static final int NEWEST = 500;

  private Listing() {}

  /** The first page of the newest rows by {@code field}, descending. */
  public static PageRequest newest(String field) {
    return PageRequest.of(0, NEWEST, Sort.by(field).descending());
  }
}
