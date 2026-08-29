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

export interface QueueItemDto {
  id: string;
  kind: 'FINDING' | 'BOARD_MEMBER' | 'ARTICLE';
  severity: string | null;
  status: string;
  excluded: boolean;
  code: string | null;
  title: string;
  description: string;
  reviewNote: string | null;
  confidence: number | null;
  snapshotId: string | null;
  excerpt: string | null;
  fields: Record<string, string | null> | null;
  evidenceItemIds: string[];
  createdAt: string;
}

export interface QueuePageDto {
  items: QueueItemDto[];
  page: number;
  size: number;
  total: number;
  findingsTotal: number;
  extractionsTotal: number;
}

export interface DecisionDto {
  id: string;
  targetType: string;
  targetId: string;
  action: string;
  reason: string | null;
  oldValue: string | null;
  newValue: string | null;
  decidedByEmail: string;
  createdAt: string;
}

export interface GateDto {
  open: number;
  needsVerification: number;
  excluded: number;
  releasable: boolean;
}

export interface SnapshotTextDto {
  id: string;
  url: string;
  pageType: string;
  fetchedAt: string;
  text: string | null;
}

export interface ReportSentenceDto {
  id: string;
  kind: 'FACTUAL' | 'STRUCTURAL';
  text: string;
  findingIds: string[];
  evidenceItemIds: string[];
  guard: 'PASS' | 'FAIL' | null;
}

export interface ReportSectionDto {
  id: string;
  title: string;
  sentences: ReportSentenceDto[];
}

export interface RoadmapActionDto {
  id: string;
  title: string;
  description: string;
  phase: 'P0_3' | 'P3_6' | 'P6_12';
  tag: 'MUST_FIX' | 'STRENGTHENS';
  completionCriterion: string;
  findingIds: string[];
}

export interface ReportExclusionDto {
  findingId: string;
  code: string;
  title: string;
  reason: string;
}

export interface ReportSummaryDto {
  id: string;
  version: number;
  status: 'DRAFT' | 'RELEASED';
  verdict: string;
  guardPassed: boolean;
  contentHash: string | null;
  narrativePromptVersion: string | null;
  createdAt: string;
  releasedAt: string | null;
}

export interface ReportDto extends ReportSummaryDto {
  auditId: string;
  sections: ReportSectionDto[];
  roadmap: RoadmapActionDto[];
  exclusions: ReportExclusionDto[];
  guardReport: string;
}

export interface ScoreDeltaDto {
  category: string;
  previous: number | null;
  current: number | null;
}

export interface GatewayDeltaDto {
  code: string;
  previous: string | null;
  current: string | null;
}

export interface DeltaDto {
  auditId: string;
  priorAuditId: string;
  scores: ScoreDeltaDto[];
  gateway: GatewayDeltaDto[];
  resolvedCodes: string[];
  newCodes: string[];
}

export interface ApiKeyDto {
  id: string;
  name: string;
  secret?: string;
  prefix: string;
  scopes: string;
  rateLimitPerMinute: number;
  createdAt?: string;
  lastUsedAt?: string | null;
  revokedAt?: string | null;
}

export interface WebhookDto {
  id: string;
  url: string;
  secret: string | null;
  events: string;
  active: boolean;
  lastStatus: number | null;
  lastDeliveryAt: string | null;
}

export interface ActionItemDto {
  id: string;
  journalId: string;
  catalogueActionId: string;
  title: string;
  description: string;
  phase: string;
  tag: string;
  completionCriterion: string;
  assigneeUserId: string | null;
  dueDate: string | null;
  status: 'OPEN' | 'IN_PROGRESS' | 'DONE';
  completionNote: string | null;
}

export interface ScheduleDto {
  id: string;
  cadence: string;
  nextRunAt: string;
  notifyEmail: boolean;
  active: boolean;
  lastAuditId: string | null;
}

export interface ScorePointDto {
  auditId: string;
  finishedAt: string;
  scores: Record<string, number>;
  mean: number;
}

export interface JournalDashboardDto {
  journalId: string;
  title: string | null;
  scoreHistory: ScorePointDto[];
  latestGateway: { code: string; outcome: string; summary: string }[];
  gauges: Record<string, number | null>;
  citationsByYear: { byYear: Record<string, number> };
  articlesByYear: { byYear: Record<string, number> };
  actions: ActionItemDto[];
  schedule: ScheduleDto | null;
}

export interface PortfolioRowDto {
  journalId: string;
  title: string | null;
  status: string;
  latestAuditId: string | null;
  lastAuditAt: string | null;
  meanScore: number | null;
  previousMeanScore: number | null;
  trend: string;
  gatewayFails: number;
  openSevereFindings: number;
  openActions: number;
}

export interface AdminOrgRowDto {
  id: string;
  name: string;
  status: string;
  createdAt: string;
  maxJournals: number;
  journals: number;
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
  reviewQueue: (auditId: string, filter = 'all', page = 0, size = 200) =>
    request<QueuePageDto>(
      `/api/v1/audits/${auditId}/review/queue?filter=${filter}&page=${page}&size=${size}`,
    ),
  reviewGate: (auditId: string) => request<GateDto>(`/api/v1/audits/${auditId}/review/gate`),
  reviewDecisions: (auditId: string) =>
    request<DecisionDto[]>(`/api/v1/audits/${auditId}/review/decisions`),
  confirmFinding: (id: string, auditId: string, note?: string) =>
    request<void>(`/api/v1/findings/${id}/confirm?auditId=${auditId}`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),
  rejectFinding: (id: string, auditId: string, reason: string) =>
    request<void>(`/api/v1/findings/${id}/reject?auditId=${auditId}`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),
  editFindingSeverity: (id: string, auditId: string, severity: string, reason: string) =>
    request<void>(`/api/v1/findings/${id}/severity?auditId=${auditId}`, {
      method: 'POST',
      body: JSON.stringify({ severity, reason }),
    }),
  annotateFinding: (id: string, auditId: string, note: string) =>
    request<void>(`/api/v1/findings/${id}/annotate?auditId=${auditId}`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    }),
  excludeFinding: (id: string, auditId: string, reason: string) =>
    request<void>(`/api/v1/findings/${id}/exclude?auditId=${auditId}`, {
      method: 'POST',
      body: JSON.stringify({ reason }),
    }),
  includeFinding: (id: string, auditId: string) =>
    request<void>(`/api/v1/findings/${id}/include?auditId=${auditId}`, { method: 'POST' }),
  correctBoardMember: (
    id: string,
    body: { name?: string; role?: string; institution?: string; country?: string; note?: string },
  ) => request<void>(`/api/v1/board-members/${id}/correct`, { method: 'POST', body: JSON.stringify(body) }),
  confirmBoardMember: (id: string) =>
    request<void>(`/api/v1/board-members/${id}/confirm`, { method: 'POST' }),
  correctArticle: (
    id: string,
    body: {
      title?: string;
      doi?: string;
      dateSubmitted?: string;
      dateAccepted?: string;
      datePublished?: string;
      abstractLanguage?: string;
      note?: string;
    },
  ) => request<void>(`/api/v1/articles/${id}/correct`, { method: 'POST', body: JSON.stringify(body) }),
  confirmArticle: (id: string) => request<void>(`/api/v1/articles/${id}/confirm`, { method: 'POST' }),
  snapshotText: (id: string) => request<SnapshotTextDto>(`/api/v1/snapshots/${id}/text`),
  generateReport: (auditId: string) =>
    request<ReportDto>(`/api/v1/audits/${auditId}/reports`, { method: 'POST' }),
  listReports: (auditId: string) => request<ReportSummaryDto[]>(`/api/v1/audits/${auditId}/reports`),
  getReport: (id: string) => request<ReportDto>(`/api/v1/reports/${id}`),
  editReportSentence: (id: string, sentenceId: string, text?: string, remove?: boolean) =>
    request<ReportDto>(`/api/v1/reports/${id}/sentences`, {
      method: 'POST',
      body: JSON.stringify({ sentenceId, text, remove }),
    }),
  releaseReport: (id: string) => request<ReportDto>(`/api/v1/reports/${id}/release`, { method: 'POST' }),
  auditDelta: (auditId: string, priorAuditId: string) =>
    request<DeltaDto>(`/api/v1/audits/${auditId}/delta/${priorAuditId}`),
  createApiKey: (body: { name: string; scopes?: string[]; rateLimitPerMinute?: number }) =>
    request<ApiKeyDto>('/api/v1/api-keys', { method: 'POST', body: JSON.stringify(body) }),
  listApiKeys: () => request<ApiKeyDto[]>('/api/v1/api-keys'),
  revokeApiKey: (id: string) => request<void>(`/api/v1/api-keys/${id}/revoke`, { method: 'POST' }),
  createWebhook: (body: { url: string; events?: string[] }) =>
    request<WebhookDto>('/api/v1/webhooks', { method: 'POST', body: JSON.stringify(body) }),
  listWebhooks: () => request<WebhookDto[]>('/api/v1/webhooks'),
  deactivateWebhook: (id: string) =>
    request<void>(`/api/v1/webhooks/${id}/deactivate`, { method: 'POST' }),
  journalDashboard: (journalId: string) =>
    request<JournalDashboardDto>(`/api/v1/journals/${journalId}/dashboard`),
  portfolio: () => request<PortfolioRowDto[]>('/api/v1/organisations/current/dashboard'),
  adoptRoadmap: (reportId: string) =>
    request<{ created: number }>(`/api/v1/reports/${reportId}/adopt-roadmap`, { method: 'POST' }),
  journalActions: (journalId: string) =>
    request<ActionItemDto[]>(`/api/v1/journals/${journalId}/actions`),
  assignAction: (id: string, body: { assigneeUserId?: string; dueDate?: string }) =>
    request<void>(`/api/v1/actions/${id}/assign`, { method: 'POST', body: JSON.stringify(body) }),
  setActionStatus: (id: string, body: { status: string; note?: string }) =>
    request<void>(`/api/v1/actions/${id}/status`, { method: 'POST', body: JSON.stringify(body) }),
  upsertSchedule: (journalId: string, body: { cadence: string; firstRunAt?: string; notifyEmail?: boolean }) =>
    request<ScheduleDto>(`/api/v1/journals/${journalId}/schedule`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),
  deactivateSchedule: (journalId: string) =>
    request<void>(`/api/v1/journals/${journalId}/schedule/deactivate`, { method: 'POST' }),
  adminOrganisations: () => request<AdminOrgRowDto[]>('/api/v1/admin/organisations'),
  adminSetQuota: (orgId: string, maxJournals: number) =>
    request<void>(`/api/v1/admin/organisations/${orgId}/quota`, {
      method: 'PATCH',
      body: JSON.stringify({ maxJournals }),
    }),
  adminSetOrgStatus: (orgId: string, status: string) =>
    request<void>(`/api/v1/admin/organisations/${orgId}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
    }),
  adminSettings: () => request<Record<string, string>>('/api/v1/admin/settings'),
  adminPutSetting: (key: string, value: string) =>
    request<void>('/api/v1/admin/settings', { method: 'PUT', body: JSON.stringify({ key, value }) }),
  adminStatus: () => request<Record<string, unknown>>('/api/v1/admin/status'),
  exportReport: async (id: string, format: 'html' | 'docx' | 'pdf') => {
    const token = session().accessToken;
    const response = await fetch(`/api/v1/reports/${id}/export?format=${format}`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) throw new Error(`Export failed (${response.status})`);
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `jrap-report.${format}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },
};
