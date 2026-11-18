import { UserManager, WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts';
import type { DashboardConfig } from './types';

// OpenID Connect, authorization code flow with PKCE: the browser never holds a client secret, and the access token lives in memory
// only (not in localStorage, where any script injected into the page could read it). A reload signs in again through the provider's
// session, which is a redirect, not a password prompt, while that session lasts.

export type Session = { token: string | null; name: string | null; signOut: () => void };

export async function startSession(config: DashboardConfig): Promise<Session | 'redirecting'> {
  if (config.auth !== 'oidc' || !config.authority || !config.clientId) {
    return { token: null, name: null, signOut: () => {} };
  }
  const redirect = `${window.location.origin}/dashboard/`;
  const manager = new UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: redirect,
    post_logout_redirect_uri: redirect,
    response_type: 'code',
    scope: config.scope ?? 'openid',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    automaticSilentRenew: false,
  });
  if (new URLSearchParams(window.location.search).has('code')) {
    const user = await manager.signinRedirectCallback();
    window.history.replaceState({}, '', redirect + window.location.hash);
    return session(manager, user.access_token, user.profile.preferred_username ?? user.profile.sub);
  }
  const user = await manager.getUser();
  if (user && !user.expired) return session(manager, user.access_token, user.profile.preferred_username ?? user.profile.sub);
  await manager.signinRedirect();
  return 'redirecting';
}

function session(manager: UserManager, token: string, name: string): Session {
  return { token, name, signOut: () => void manager.signoutRedirect() };
}
