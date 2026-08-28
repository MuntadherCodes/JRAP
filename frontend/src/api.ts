// Minimal API client for /api/v1 (SRS §3.2.2). Tokens are kept in memory only.
export interface UserDto {
  id: string;
  organisationId: string;
  email: string;
  displayName: string;
  role: 'OWNER' | 'ANALYST' | 'VIEWER';
  status: string;
  totpEnabled: boolean;
}

export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  user: UserDto;
}

let accessToken: string | null = null;
let refreshToken: string | null = null;
let currentUser: UserDto | null = null;

export function session() {
  return { accessToken, user: currentUser };
}

export function storeSession(tokens: TokenResponse) {
  accessToken = tokens.accessToken;
  refreshToken = tokens.refreshToken;
  currentUser = tokens.user;
}

export function clearSession() {
  accessToken = null;
  refreshToken = null;
  currentUser = null;
}

export class ApiError extends Error {
  constructor(public status: number, public problem: { title?: string; detail?: string }) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
  }
}

async function request<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
  const response = await fetch(path, { ...options, headers });
  if (response.status === 401 && retry && refreshToken) {
    const refreshed = await fetch('/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (refreshed.ok) {
      storeSession(await refreshed.json());
      return request<T>(path, options, false);
    }
    clearSession();
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new ApiError(response.status, problem);
  }
  if (response.status === 204) return undefined as T;
  return response.json();
}

export const api = {
  register: (body: { organisationName: string; email: string; password: string; displayName: string }) =>
    request<{ organisationId: string }>('/api/v1/auth/register', { method: 'POST', body: JSON.stringify(body) }),
  verifyEmail: (token: string) =>
    request<void>('/api/v1/auth/verify-email', { method: 'POST', body: JSON.stringify({ token }) }),
  login: (body: { email: string; password: string; totpCode?: string }) =>
    request<TokenResponse>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  acceptInvitation: (body: { token: string; password: string; displayName: string }) =>
    request<void>('/api/v1/auth/accept-invitation', { method: 'POST', body: JSON.stringify(body) }),
  me: () => request<UserDto>('/api/v1/me'),
  listUsers: () => request<UserDto[]>('/api/v1/organisations/current/users'),
  invite: (body: { email: string; role: string }) =>
    request<{ userId: string }>('/api/v1/organisations/current/invitations', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};
