import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { api, JournalDashboardDto, session, UserDto } from '../api';
import { Empty, Loading, OutcomeBadge, StatusBadge } from '../components/ui';

const fmtLength = (v: number) => v.toLocaleString('en-US').length;

/** Clean y-axis ticks: a 1/2/5×10ⁿ step so ~3 gridlines cover the maximum. */
function niceTicks(max: number): { top: number; ticks: number[] } {
  if (max <= 0) return { top: 1, ticks: [0, 1] };
  const pow = Math.pow(10, Math.floor(Math.log10(max / 3)));
  const step = Math.max(1, // counts: never a fractional gridline
    [1, 2, 5, 10].map(m => m * pow).find(s => max / s <= 3.5) ?? 10 * pow);
  const top = Math.ceil(max / step) * step;
  const ticks: number[] = [];
  for (let v = 0; v <= top; v += step) ticks.push(v);
  return { top, ticks };
}

/**
 * Dependency-free column chart for per-year counts: single series, baseline axis,
 * hairline gridlines with clean ticks, rounded data-ends (square at the baseline),
 * per-band hover tooltip, and the peak directly labeled. Values, labels and axis
 * text wear text tokens — only the marks carry the series color.
 */
export function YearColumns({ data, color, emptyText, name }:
    { data: Record<string, number>; color: string; emptyText: string; name: string }) {
  const [hover, setHover] = useState<number | null>(null);
  const entries = Object.entries(data)
    .map(([year, value]) => [year, Number(value)] as const)
    .filter(([year]) => /^\d{4}$/.test(year))
    .sort(([a], [b]) => a.localeCompare(b))
    .slice(-10);
  if (entries.length === 0) return <p className="chart-empty">{emptyText}</p>;

  const values = entries.map(([, v]) => v);
  const max = Math.max(...values);
  const { top, ticks } = niceTicks(max);
  const band = 42;
  const barWidth = 22; // ≤ 24px — the band's leftover stays air
  const plotHeight = 130;
  const padTop = 16, padBottom = 24, padRight = 6;
  const padLeft = Math.max(40, 14 + fmtLength(top) * 6.2); // room for the widest tick label
  const width = padLeft + entries.length * band + padRight;
  const height = padTop + plotHeight + padBottom;
  const baseline = padTop + plotHeight;
  const yOf = (v: number) => baseline - (v / top) * plotHeight;
  const peakIndex = values.indexOf(max);
  const fmt = (v: number) => v.toLocaleString('en-US');

  /** Bar path: 4px-rounded data end, square at the baseline. */
  const bar = (i: number, v: number) => {
    const x = padLeft + i * band + (band - barWidth) / 2;
    const yTop = yOf(v);
    const r = Math.min(4, Math.max(0, baseline - yTop));
    return `M${x},${baseline} L${x},${yTop + r} Q${x},${yTop} ${x + r},${yTop} `
        + `L${x + barWidth - r},${yTop} Q${x + barWidth},${yTop} ${x + barWidth},${yTop + r} `
        + `L${x + barWidth},${baseline} Z`;
  };

  return (
    <div className="chart-wrap" onMouseLeave={() => setHover(null)}>
      <svg width={width} height={height} role="img"
           aria-label={`${name}: ${entries.map(([y, v]) => `${y} ${fmt(v)}`).join(', ')}`}>
        {ticks.map(tick => (
          <g key={tick}>
            <line x1={padLeft} x2={width - padRight} y1={yOf(tick)} y2={yOf(tick)}
                  stroke={tick === 0 ? 'var(--border-2)' : 'var(--border)'} strokeWidth={1} />
            <text x={padLeft - 6} y={yOf(tick) + 3} textAnchor="end" fontSize={10}
                  fill="var(--muted)" style={{ fontVariantNumeric: 'tabular-nums' }}>
              {fmt(tick)}
            </text>
          </g>
        ))}
        {entries.map(([year, value], i) => {
          const cx = padLeft + i * band + band / 2;
          return (
            <g key={year}>
              <path d={bar(i, value)} fill={color} opacity={hover === null || hover === i ? 1 : 0.45}>
                <title>{`${year}: ${fmt(value)}`}</title>
              </path>
              {(i === peakIndex || hover === i) && (
                <text x={cx} y={yOf(value) - 5} textAnchor="middle" fontSize={10.5}
                      fill="var(--text-2)" fontWeight={600}
                      style={{ fontVariantNumeric: 'tabular-nums' }}>
                  {fmt(value)}
                </text>
              )}
              <text x={cx} y={height - 8} textAnchor="middle" fontSize={10} fill="var(--muted)">
                {year}
              </text>
              {/* hit target: the whole band, larger than the mark */}
              <rect x={padLeft + i * band} y={padTop} width={band} height={plotHeight + padBottom}
                    fill="transparent" onMouseEnter={() => setHover(i)} />
            </g>
          );
        })}
      </svg>
    </div>
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
    return <main className="content">{error ? <p className="error">{error}</p> : <Loading />}</main>;
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

      <div style={{ display: 'flex', gap: 40, flexWrap: 'wrap', alignItems: 'flex-start' }}>
        <div>
          <h2>{t('citationsPerYear')}</h2>
          <YearColumns data={dash.citationsByYear.byYear} color="#4353c9"
                       emptyText={t('noCitationData')} name={t('citationsPerYear')} />
        </div>
        <div>
          <h2>{t('articlesPerYear')}</h2>
          <YearColumns data={dash.articlesByYear.byYear} color="#c2571b"
                       emptyText={t('noArticleData')} name={t('articlesPerYear')} />
        </div>
      </div>

      <h2>{t('diversityGauges')}</h2>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
        {Object.entries(dash.gauges)
          .filter(([, value]) => value != null)
          .map(([name, value]) => (
            <div key={name} className="card" style={{ padding: '10px 16px', minWidth: 160 }}>
              <div className="secondary" style={{ fontSize: 12 }}>
                {t(`gauge_${name}`, { defaultValue: name.replace(/_/g, ' ') })}
              </div>
              <strong style={{ fontSize: 22 }}>{Number(value).toFixed(2)}</strong>
            </div>
          ))}
      </div>

      <h2>{t('gatewayChecks')}</h2>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {dash.latestGateway.map(g => (
          <span key={g.code} title={g.summary} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
            <strong style={{ fontSize: '0.85rem' }}>{g.code}</strong> <OutcomeBadge outcome={g.outcome} />
          </span>
        ))}
      </div>

      <h2>{t('actionBoard')} ({dash.actions.filter(a => a.status !== 'DONE').length} {t('open')})</h2>
      {dash.actions.length === 0 ? (
        <Empty>{t('noActions')}</Empty>
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
                <td><StatusBadge status={action.status} /></td>
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
