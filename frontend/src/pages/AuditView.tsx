import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { AnalysisDto, api, AuditDto, AuditFindingDto, BoardMemberDto, ExtractedArticleDto, ReportSummaryDto, SkippedUrlDto, SnapshotDto } from '../api';
import { Loading, OutcomeBadge, PipelineStepper, SeverityBadge, StatusBadge } from '../components/ui';

const ACTIVE = ['PENDING', 'RUNNING'];

export default function AuditView() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [audit, setAudit] = useState<AuditDto | null>(null);
  const [snapshots, setSnapshots] = useState<SnapshotDto[]>([]);
  const [skipped, setSkipped] = useState<SkippedUrlDto[]>([]);
  const [board, setBoard] = useState<BoardMemberDto[]>([]);
  const [articles, setArticles] = useState<ExtractedArticleDto[]>([]);
  const [analysis, setAnalysis] = useState<AnalysisDto | null>(null);
  const [auditFindings, setAuditFindings] = useState<AuditFindingDto[]>([]);
  const [reports, setReports] = useState<ReportSummaryDto[]>([]);
  const navigate = useNavigate();
  const navigateReport = (reportId: string) => navigate(`/reports/${reportId}`);

  useEffect(() => {
    if (!id) return;
    let timer: ReturnType<typeof setTimeout>;
    const refresh = async () => {
      try {
        const current = await api.audit(id);
        setAudit(current);
        if (!ACTIVE.includes(current.status)) {
          setSnapshots(await api.auditSnapshots(id));
          setSkipped(await api.auditSkipped(id));
          setBoard(await api.auditBoard(id).catch(() => []));
          setArticles(await api.auditArticles(id).catch(() => []));
          setAnalysis(await api.auditAnalysis(id).catch(() => null));
          setAuditFindings(await api.auditFindings(id).catch(() => []));
          setReports(await api.listReports(id).catch(() => []));
          return; // terminal — stop polling
        }
      } catch {
        return;
      }
      timer = setTimeout(refresh, 5000); // NFR-PERF-1: live status while running
    };
    refresh();
    return () => clearTimeout(timer);
  }, [id]);

  if (!audit) {
    return (
      <main className="content">
        <Loading />
      </main>
    );
  }

  const byType = snapshots.reduce<Record<string, number>>((acc, s) => {
    acc[s.pageType] = (acc[s.pageType] ?? 0) + 1;
    return acc;
  }, {});

  return (
    <main className="content">
      <p>
        <Link to={`/journals/${audit.journalId}`}>← {t('journals')}</Link>
      </p>
      <h1 style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        {t('audit')} <StatusBadge status={audit.status} />
      </h1>
      <PipelineStepper stage={audit.stage} terminal={!ACTIVE.includes(audit.status)} />
      <p style={{ color: 'var(--muted)', display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span>{t('pagesFetched')}: {audit.pagesFetched}/{audit.pageCap}</span>
        <span className="progress" style={{ flex: '0 1 240px' }}>
          <div style={{ width: `${Math.min(100, (audit.pagesFetched / Math.max(1, audit.pageCap)) * 100)}%` }} />
        </span>
        <span>{t('pagesSkipped')}: {audit.pagesSkipped}</span>
      </p>
      {audit.error && <p className="error">{audit.error}</p>}
      {ACTIVE.includes(audit.status) && (
        <button className="secondary" onClick={() => api.cancelAudit(audit.id)}>
          {t('cancel')}
        </button>
      )}
      {!ACTIVE.includes(audit.status) && (
        <>
          {analysis && analysis.gateway.length > 0 && (
            <p style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <Link to={`/audits/${audit.id}/review`}>
                <button>{t('openReviewQueue')}</button>
              </Link>
              <button
                className="secondary"
                onClick={async () => {
                  const report = await api.generateReport(audit.id);
                  setReports(await api.listReports(audit.id));
                  navigateReport(report.id);
                }}
              >
                {t('generateReport')}
              </button>
            </p>
          )}
          {reports.length > 0 && (
            <>
              <h2>{t('reports')}</h2>
              <table>
                <thead>
                  <tr>
                    <th>v</th>
                    <th>{t('status')}</th>
                    <th>{t('verdict')}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {reports.map(r => (
                    <tr key={r.id}>
                      <td>{r.version}</td>
                      <td><StatusBadge status={r.status} /></td>
                      <td><StatusBadge status={r.verdict === 'NOT_READY' ? 'REJECTED' : r.verdict === 'READY' ? 'CONFIRMED' : 'DRAFT'} /> {r.verdict.replace('_', ' ')}</td>
                      <td>
                        <Link to={`/reports/${r.id}`}>{t('openReport')}</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          {analysis && analysis.gateway.length > 0 && (
            <>
              <h2>{t('gatewayChecks')}</h2>
              <table>
                <thead>
                  <tr>
                    <th>#</th>
                    <th>{t('outcome')}</th>
                    <th>{t('summary')}</th>
                  </tr>
                </thead>
                <tbody>
                  {analysis.gateway.map((g) => (
                    <tr key={g.code}>
                      <td>{g.code}</td>
                      <td><OutcomeBadge outcome={g.outcome} /></td>
                      <td>{g.summary}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <h2>
                {t('csabScores')} {analysis.rubricVersion && `(rubric v${analysis.rubricVersion})`}
              </h2>
              <table>
                <tbody>
                  {analysis.scores.map((s) => (
                    <tr key={s.category}>
                      <td style={{ width: '12rem' }}>{s.category}</td>
                      <td>
                        <strong>{s.score}</strong> / 5
                      </td>
                      <td style={{ width: '60%' }}>
                        <div className="scorebar">
                          <div
                            style={{
                              width: `${(s.score / 5) * 100}%`,
                              background: s.score >= 4 ? 'var(--good)' : s.score >= 2 ? 'var(--warn)' : 'var(--danger)',
                            }}
                          />
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          {auditFindings.length > 0 && (
            <>
              <h2>
                {t('redFlags')} ({auditFindings.length})
              </h2>
              {auditFindings.map((f) => (
                <div key={f.id} className="card" style={{ padding: '0.9rem 1.1rem' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                    <SeverityBadge severity={f.severity} />
                    <code>{f.code}</code>
                    <strong>{f.title}</strong>
                    {f.status === 'NEEDS_VERIFICATION' && <StatusBadge status={f.status} />}
                  </div>
                  <p style={{ margin: '0.4rem 0 0', color: 'var(--text-2)' }}>{f.description}</p>
                </div>
              ))}
            </>
          )}
          {board.length > 0 && (
            <>
              <h2>
                {t('editorialBoard')} ({board.length})
              </h2>
              <table>
                <thead>
                  <tr>
                    <th>{t('name')}</th>
                    <th>{t('role')}</th>
                    <th>{t('institution')}</th>
                    <th>{t('country')}</th>
                    <th>{t('confidence')}</th>
                  </tr>
                </thead>
                <tbody>
                  {board.map((m) => (
                    <tr key={m.id}>
                      <td>
                        {m.name} {m.needsReview && <span title="needs review">⚠</span>}
                      </td>
                      <td>{m.role}</td>
                      <td>{m.institution}</td>
                      <td>{m.country}</td>
                      <td>{m.confidence}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          {articles.length > 0 && (
            <>
              <h2>
                {t('articles')} ({articles.length})
              </h2>
              <table>
                <thead>
                  <tr>
                    <th>{t('journalTitle')}</th>
                    <th>{t('authors')}</th>
                    <th>DOI</th>
                    <th>{t('published')}</th>
                    <th>{t('refs')}</th>
                  </tr>
                </thead>
                <tbody>
                  {articles.slice(0, 100).map((a) => (
                    <tr key={a.id}>
                      <td>
                        {a.title ?? '—'} {a.needsReview && <span title="needs review">⚠</span>}
                      </td>
                      <td>{a.authors.map((au) => au.name).join('; ')}</td>
                      <td style={{ wordBreak: 'break-all' }}>{a.doi}</td>
                      <td>{a.datePublished}</td>
                      <td>{a.referencesCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
          <h2>{t('pageInventory')}</h2>
          <table>
            <thead>
              <tr>
                <th>{t('pageType')}</th>
                <th>{t('count')}</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(byType)
                .sort((a, b) => b[1] - a[1])
                .map(([type, count]) => (
                  <tr key={type}>
                    <td>{type}</td>
                    <td>{count}</td>
                  </tr>
                ))}
            </tbody>
          </table>
          {skipped.length > 0 && (
            <>
              <h2>
                {t('skippedUrls')} ({skipped.length})
              </h2>
              <table>
                <thead>
                  <tr>
                    <th>URL</th>
                    <th>{t('status')}</th>
                    <th>{t('reason')}</th>
                  </tr>
                </thead>
                <tbody>
                  {skipped.map((s) => (
                    <tr key={s.url}>
                      <td style={{ wordBreak: 'break-all' }}>{s.url}</td>
                      <td>{s.status}</td>
                      <td>{s.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </>
      )}
    </main>
  );
}
