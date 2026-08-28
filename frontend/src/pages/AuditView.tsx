import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { api, AuditDto, BoardMemberDto, ExtractedArticleDto, SkippedUrlDto, SnapshotDto } from '../api';

const ACTIVE = ['PENDING', 'RUNNING'];

export default function AuditView() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [audit, setAudit] = useState<AuditDto | null>(null);
  const [snapshots, setSnapshots] = useState<SnapshotDto[]>([]);
  const [skipped, setSkipped] = useState<SkippedUrlDto[]>([]);
  const [board, setBoard] = useState<BoardMemberDto[]>([]);
  const [articles, setArticles] = useState<ExtractedArticleDto[]>([]);

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
