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

export interface JournalDto {
  id: string;
  status: string;
  title: string | null;
  publisher: string | null;
  country: string | null;
  issnL: string | null;
  issnPrint: string | null;
  issnOnline: string | null;
  platform: string | null;
  homepageUrl: string | null;
  openalexId: string | null;
  doajId: string | null;
  inCrossref: boolean;
  inDoaj: boolean;
  createdAt: string;
}

export interface IdentityRecordDto {
  source: string;
  availability: 'OK' | 'NOT_FOUND' | 'UNAVAILABLE';
  title: string | null;
  publisher: string | null;
  country: string | null;
  issnPrint: string | null;
  issnOnline: string | null;
  issnL: string | null;
  apiRecordId: string | null;
  retrievedAt: string;
}

export interface JournalDetailDto {
  journal: JournalDto;
  identity: IdentityRecordDto[];
}

export interface FindingDto {
  id: string;
  category: string;
  code: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
  status: string;
  title: string;
  description: string;
  detectorVersion: string;
  createdAt: string;
  evidenceItemIds: string[];
}

export interface AuditDto {
  id: string;
  journalId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETE' | 'FAILED' | 'CANCELLED';
  stage: string;
  pageCap: number;
  pagesFetched: number;
  pagesSkipped: number;
  articlesExtracted: number;
  boardMembersExtracted: number;
  error: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface SnapshotDto {
  id: string;
  url: string;
  httpStatus: number;
  contentType: string | null;
  pageType: string;
  fetchedAt: string;
}

export interface SkippedUrlDto {
  url: string;
  status: string;
  reason: string | null;
  at: string | null;
}

export interface ExtractionSummaryDto {
  boardMembers: number;
  boardMembersNeedingReview: number;
  articles: number;
  articlesNeedingReview: number;
}

export interface BoardMemberDto {
  id: string;
  name: string;
  role: string | null;
  institution: string | null;
  country: string | null;
  method: string;
  confidence: number;
  needsReview: boolean;
}

export interface ExtractedAuthorDto {
  position: number;
  name: string;
  affiliation: string | null;
  country: string | null;
}

export interface ExtractedArticleDto {
  id: string;
  title: string | null;
  doi: string | null;
  datePublished: string | null;
  dateSubmitted: string | null;
  dateAccepted: string | null;
  titleScript: string | null;
  abstractLanguage: string | null;
  referencesCount: number;
  referencesRomanShare: number | null;
  method: string;
  confidence: number;
  needsReview: boolean;
  authors: ExtractedAuthorDto[];
}

export interface GatewayDto {
  code: string;
  outcome: 'PASS' | 'PASS_WITH_CAVEATS' | 'FAIL' | 'UNCLEAR';
  summary: string;
}

export interface ScoreDto {
  category: string;
  score: number;
  criteria: string;
}

export interface MetricDto {
  name: string;
  value: number | null;
  detail: string;
}

export interface AnalysisDto {
  rubricVersion: string | null;
  gateway: GatewayDto[];
  scores: ScoreDto[];
  metrics: MetricDto[];
}

export interface AuditFindingDto {
  id: string;
  category: string;
  code: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
  status: string;
  title: string;
  description: string;
  detectorVersion: string;
  createdAt: string;
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
  registerJournal: (body: { issn?: string; url?: string }) =>
    request<JournalDto>('/api/v1/journals', { method: 'POST', body: JSON.stringify(body) }),
  listJournals: () => request<JournalDto[]>('/api/v1/journals'),
  journalDetail: (id: string) => request<JournalDetailDto>(`/api/v1/journals/${id}`),
  journalFindings: (id: string) => request<FindingDto[]>(`/api/v1/journals/${id}/findings`),
  createAudit: (journalId: string) =>
    request<AuditDto>(`/api/v1/journals/${journalId}/audits`, { method: 'POST' }),
  listAudits: (journalId: string) => request<AuditDto[]>(`/api/v1/journals/${journalId}/audits`),
  audit: (id: string) => request<AuditDto>(`/api/v1/audits/${id}`),
  auditSnapshots: (id: string) => request<SnapshotDto[]>(`/api/v1/audits/${id}/snapshots`),
  auditSkipped: (id: string) => request<SkippedUrlDto[]>(`/api/v1/audits/${id}/skipped`),
  cancelAudit: (id: string) => request<void>(`/api/v1/audits/${id}/cancel`, { method: 'POST' }),
  extractionSummary: (id: string) =>
    request<ExtractionSummaryDto>(`/api/v1/audits/${id}/extraction-summary`),
  auditBoard: (id: string) => request<BoardMemberDto[]>(`/api/v1/audits/${id}/board`),
  auditArticles: (id: string) => request<ExtractedArticleDto[]>(`/api/v1/audits/${id}/articles`),
  auditAnalysis: (id: string) => request<AnalysisDto>(`/api/v1/audits/${id}/analysis`),
  auditFindings: (id: string) => request<AuditFindingDto[]>(`/api/v1/audits/${id}/findings`),
};
