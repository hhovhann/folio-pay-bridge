package dev.foliopay.config.security;

import dev.foliopay.service.support.Fingerprint;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The API keys this service accepts, for integrators that cannot use OAuth2 (D-30). Configured as
 * {@code name:sha256hex:scope scope,name2:...} from Secrets Manager: only the SHA-256 of a key is ever configured, so the secret itself is
 * shown once when it is made ({@code tools/new_api_key.py}) and never stored. Every lookup compares against every key in constant time.
 */
public final class ApiKeys {
  /** A client and what it may do, as {@code SCOPE_} authorities like a token's. */
  public record Client(String name, Set<String> scopes) {}

  private record Entry(Client client, byte[] keyHash) {}

  private final List<Entry> entries = new ArrayList<>();

  public ApiKeys(String config) {
    for (String raw : config.split(",")) {
      if (raw.isBlank()) {
        continue;
      }
      String[] parts = raw.trim().split(":", 3);
      if (parts.length != 3 || parts[0].isBlank() || !parts[1].matches("[0-9a-fA-F]{64}") || parts[2].isBlank()) {
        throw new IllegalStateException("foliopay.auth.api-keys entries must be name:sha256hex:scope[ scope...]");
      }
      Set<String> scopes = Arrays.stream(parts[2].trim().split("\\s+")).collect(Collectors.toUnmodifiableSet());
      entries.add(new Entry(new Client(parts[0], scopes), HexFormat.of().parseHex(parts[1])));
    }
  }

  public boolean isEmpty() { return entries.isEmpty(); }

  public Optional<Client> find(String presentedKey) {
    byte[] hash = Fingerprint.sha256(presentedKey);
    Client found = null;
    for (Entry entry : entries) {
      if (MessageDigest.isEqual(entry.keyHash(), hash)) {
        found = entry.client();
      }
    }
    return Optional.ofNullable(found);
  }
}
