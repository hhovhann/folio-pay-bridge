package dev.foliopay.service;

import java.util.List;

/** Demo-only operations: simulator switches, a view of what the vault stores, and the masked log tail. Absent unless demo controls are on. */
public interface DemoService {
  record VaultRow(String token, String bin6, String last4, String sealedPanHex, String wrappedKeyHex, String keyId) {}

  record SimState(String opera, String psp, int pspCaptures, long pspCapturedMinor, int operaPosts) {}

  record SimCommand(String opera, String psp, boolean reset) {}

  SimState state();

  SimState apply(SimCommand command);

  List<VaultRow> vaultRows();

  List<String> recentLogs(int lines);
}
