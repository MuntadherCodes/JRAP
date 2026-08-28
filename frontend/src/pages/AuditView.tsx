import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { AnalysisDto, api, AuditDto, AuditFindingDto, BoardMemberDto, ExtractedArticleDto, SkippedUrlDto, SnapshotDto } from '../api';

const ACTIVE = ['PENDING', 'RUNNING'];

const outcomeColor: Record<string, string> = {
  PASS: '#1b7f4d',
  PASS_WITH_CAVEATS: '#a06a00',
  FAIL: '#b3261e',
  UNCLEAR: '#6a6f85',
};


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
        <p>…</p>
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
      <h1>
        {t('audit')} — {audit.status}
      </h1>
      <p style={{ color: 'var(--muted)' }}>
        {t('stage')}: {audit.stage} · {t('pagesFetched')}: {audit.pagesFetched}/{audit.pageCap} ·{' '}
        {t('pagesSkipped')}: {audit.pagesSkipped}
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
                      <td style={{ color: outcomeColor[g.outcome], fontWeight: 600 }}>{g.outcome}</td>
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
                        <div style={{ background: '#ececf2', borderRadius: 4, height: 10 }}>
                          <div
                            style={{
                              width: `${(s.score / 5) * 100}%`,
                              background: s.score >= 4 ? '#1b7f4d' : s.score >= 2 ? '#a06a00' : '#b3261e',
                              height: 10,
                              borderRadius: 4,
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
                <div key={f.id} className="card" style={{ maxWidth: 'none', marginBottom: '0.8rem', padding: '1rem' }}>
                  <strong>
                    [{f.severity}] {f.code} — {f.title}
                  </strong>
                  {f.status === 'NEEDS_VERIFICATION' && (
                    <span style={{ color: '#a06a00', marginInlineStart: '0.5rem' }}>
                      ({t('needsVerification')})
                    </span>
                  )}
                  <p style={{ margin: '0.4rem 0 0' }}>{f.description}</p>
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
