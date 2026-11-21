package dev.foliopay.service;

import dev.foliopay.domain.model.SettlementLine;
import java.util.List;

/** Demo-only operations: simulator switches, a view of what the vault stores, and the masked log tail. Absent unless demo controls are on. */
public interface DemoService {
  record VaultRow(String token, String bin6, String last4, String sealedPanHex, String wrappedKeyHex, String keyId) {
    /** The console shows what the vault stores; a log line through this object shows none of it. */
    @Override
    public String toString() {
      return "VaultRow[token=[redacted], bin6=" + bin6 + ", last4=" + last4 + ", sealedPanHex=[redacted], wrappedKeyHex=[redacted], keyId="
          + keyId + "]";
    }
  }

  record SimState(String opera, String psp, int pspCaptures, long pspCapturedMinor, int operaPosts) {}

  record SimCommand(String opera, String psp, boolean reset) {}

  SimState state();

  SimState apply(SimCommand command);

  List<VaultRow> vaultRows();

  List<String> recentLogs(int lines);

  /** The acquirer's own statement with one capture missing, one settled short and one line we never captured, to show reconciliation. */
  List<SettlementLine> messyAcquirerStatement();
}
