import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { api, JournalDashboardDto, session, UserDto } from '../api';

const outcomeColor: Record<string, string> = {
  PASS: '#1b7f4d',
  PASS_WITH_CAVEATS: '#a06a00',
  FAIL: '#b3261e',
  UNCLEAR: '#6a6f85',
};

/** Tiny dependency-free bar chart. */
function Bars({ data, color }: { data: Record<string, number>; color: string }) {
  const entries = Object.entries(data).sort(([a], [b]) => a.localeCompare(b)).slice(-10);
  if (entries.length === 0) return <p className="secondary">—</p>;
  const max = Math.max(...entries.map(([, v]) => v), 1);
  const width = entries.length * 34;
  return (
    <svg width={width} height={90} role="img">
      {entries.map(([year, value], i) => {
        const h = Math.max(2, (value / max) * 60);
        return (
          <g key={year}>
            <rect x={i * 34 + 4} y={70 - h} width={24} height={h} fill={color} rx={2} />
            <text x={i * 34 + 16} y={68 - h} textAnchor="middle" fontSize={9}>{value}</text>
            <text x={i * 34 + 16} y={84} textAnchor="middle" fontSize={9} fill="#6a6f85">
              {year.slice(-2)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

/** FR-DASH-1: latest scores, gateway, trends, diversity gauges, open actions. */
export default function JournalDashboard() {
  const { t } = useTranslation();
  const { id } = useParams();
  const [dash, setDash] = useState<JournalDashboardDto | null>(null);
  const [users, setUsers] = useState<UserDto[]>([]);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (!id) return;
    try {
      setDash(await api.journalDashboard(id));
      setUsers(await api.listUsers().catch(() => []));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [id]);

  useEffect(() => {
    load();
  }, [load]);

  if (!dash) {
    return <main className="content"><p>{error || '…'}</p></main>;
  }

  const act = async (fn: () => Promise<unknown>) => {
    setError('');
    try {
      await fn();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const viewer = session().user?.role === 'VIEWER';
  const userName = (uid: string | null) =>
    users.find(u => u.id === uid)?.displayName ?? (uid ? '…' : '—');

  return (
    <main className="content" style={{ maxWidth: 1000 }}>
      <p><Link to={`/journals/${id}`}>← {dash.title ?? t('journals')}</Link></p>
      <h1>{t('journalDashboard')}</h1>
      {error && <p className="error">{error}</p>}

      <h2>{t('scoreHistory')}</h2>
      {dash.scoreHistory.length === 0 ? <p className="secondary">—</p> : (
        <table>
          <thead>
            <tr>
              <th>{t('audit')}</th>
              <th>policy</th><th>content</th><th>standing</th><th>regularity</th><th>availability</th>
              <th>{t('mean')}</th>
            </tr>
          </thead>
          <tbody>
            {dash.scoreHistory.map(point => (
              <tr key={point.auditId}>
                <td><Link to={`/audits/${point.auditId}`}>{point.finishedAt?.slice(0, 10)}</Link></td>
                {['policy', 'content', 'standing', 'regularity', 'availability'].map(cat => (
                  <td key={cat}>{point.scores[cat] ?? '—'}</td>
                ))}
                <td><strong>{point.mean}</strong></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <div style={{ display: 'flex', gap: 32, flexWrap: 'wrap' }}>
        <div>
          <h2>{t('citationsPerYear')}</h2>
          <Bars data={dash.citationsByYear.byYear} color="#4650dd" />
        </div>
        <div>
          <h2>{t('articlesPerYear')}</h2>
          <Bars data={dash.articlesByYear.byYear} color="#1b7f4d" />
        </div>
      </div>

      <h2>{t('diversityGauges')}</h2>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {Object.entries(dash.gauges).map(([name, value]) => (
          <div key={name} className="card" style={{ padding: '8px 14px' }}>
            <div className="secondary" style={{ fontSize: 12 }}>{name}</div>
            <strong style={{ fontSize: 20 }}>{value == null ? '—' : Number(value).toFixed(2)}</strong>
          </div>
        ))}
      </div>

      <h2>{t('gatewayChecks')}</h2>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {dash.latestGateway.map(g => (
          <span key={g.code} className="card" title={g.summary}
                style={{ padding: '6px 10px', color: outcomeColor[g.outcome] ?? '#333', fontWeight: 600 }}>
            {g.code}: {g.outcome.replace(/_/g, ' ')}
          </span>
        ))}
      </div>

      <h2>{t('actionBoard')} ({dash.actions.filter(a => a.status !== 'DONE').length} {t('open')})</h2>
      {dash.actions.length === 0 ? (
        <p className="secondary">{t('noActions')}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>{t('action')}</th><th>{t('months')}</th><th>{t('tag')}</th>
              <th>{t('assignee')}</th><th>{t('dueDate')}</th><th>{t('status')}</th><th></th>
            </tr>
          </thead>
          <tbody>
            {dash.actions.map(action => (
              <tr key={action.id} style={{ opacity: action.status === 'DONE' ? 0.55 : 1 }}>
                <td title={action.description + ' — ' + action.completionCriterion}>
                  <strong>{action.title}</strong>
                </td>
                <td>{action.phase === 'P0_3' ? '0–3' : action.phase === 'P3_6' ? '3–6' : '6–12'}</td>
                <td style={{ color: action.tag === 'MUST_FIX' ? '#b3261e' : '#1b7f4d' }}>
                  {action.tag === 'MUST_FIX' ? t('mustFix') : t('strengthens')}
                </td>
                <td>
                  {viewer ? userName(action.assigneeUserId) : (
                    <select
                      value={action.assigneeUserId ?? ''}
                      onChange={e => act(() => api.assignAction(action.id, {
                        assigneeUserId: e.target.value || undefined,
                        dueDate: action.dueDate ?? undefined,
                      }))}
                    >
                      <option value="">—</option>
                      {users.map(u => <option key={u.id} value={u.id}>{u.displayName}</option>)}
                    </select>
                  )}
                </td>
                <td>
                  {viewer ? (action.dueDate ?? '—') : (
                    <input type="date" value={action.dueDate ?? ''}
                           onChange={e => act(() => api.assignAction(action.id, {
                             assigneeUserId: action.assigneeUserId ?? undefined,
                             dueDate: e.target.value || undefined,
                           }))} />
                  )}
                </td>
                <td>{action.status}</td>
                <td>
                  {!viewer && action.status !== 'DONE' && (
                    <>
                      {action.status === 'OPEN' && (
                        <button className="secondary" onClick={() => act(() =>
                          api.setActionStatus(action.id, { status: 'IN_PROGRESS' }))}>
                          {t('start')}
                        </button>
                      )}{' '}
                      <button onClick={() => {
                        const note = window.prompt(t('completionNotePrompt'));
                        if (note) act(() => api.setActionStatus(action.id, { status: 'DONE', note }));
                      }}>{t('markDone')}</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <h2>{t('reauditSchedule')}</h2>
      <div className="card">
        {dash.schedule && dash.schedule.active ? (
          <>
            {t('scheduleActive', { cadence: dash.schedule.cadence })} · {t('nextRun')}:{' '}
            {dash.schedule.nextRunAt?.slice(0, 16).replace('T', ' ')}
            {!viewer && (
              <>{' '}
                <button className="secondary"
                        onClick={() => act(() => api.deactivateSchedule(id!))}>
                  {t('pause')}
                </button>
              </>
            )}
          </>
        ) : viewer ? (
          <span className="secondary">{t('noSchedule')}</span>
        ) : (
          <span>
            {t('noSchedule')}{' '}
            {['MONTHLY', 'QUARTERLY', 'SEMIANNUAL', 'ANNUAL'].map(cadence => (
              <button key={cadence} className="secondary" style={{ marginInlineEnd: 6 }}
                      onClick={() => act(() => api.upsertSchedule(id!, { cadence }))}>
                {cadence.toLowerCase()}
              </button>
            ))}
          </span>
        )}
      </div>
    </main>
  );
}
