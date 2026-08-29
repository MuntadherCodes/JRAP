import { FormEvent, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { api, ApiKeyDto, PortfolioRowDto, session, UserDto, WebhookDto } from '../api';

const trendGlyph: Record<string, string> = { up: '▲', down: '▼', flat: '→', '—': '—' };
const trendColor: Record<string, string> = { up: '#1b7f4d', down: '#b3261e', flat: '#6a6f85' };

export default function Dashboard() {
  const { t } = useTranslation();
  const user = session().user;
  const [users, setUsers] = useState<UserDto[]>([]);
  const [inviteEmail, setInviteEmail] = useState('');
  const [inviteRole, setInviteRole] = useState('ANALYST');
  const [error, setError] = useState<string | null>(null);
  const [portfolio, setPortfolio] = useState<PortfolioRowDto[]>([]);
  const [keys, setKeys] = useState<ApiKeyDto[]>([]);
  const [newSecret, setNewSecret] = useState<string | null>(null);
  const [keyName, setKeyName] = useState('');
  const [keyWrite, setKeyWrite] = useState(false);
  const [webhooks, setWebhooks] = useState<WebhookDto[]>([]);
  const [webhookUrl, setWebhookUrl] = useState('');
  const [webhookSecret, setWebhookSecret] = useState<string | null>(null);
  const canManage = user?.role === 'OWNER' || user?.role === 'ANALYST';
  const owner = user?.role === 'OWNER';

  const load = () => {
    api.portfolio().then(setPortfolio).catch(() => setPortfolio([]));
    if (canManage) {
      api.listUsers().then(setUsers).catch(() => setUsers([]));
    }
    if (owner) {
      api.listApiKeys().then(setKeys).catch(() => setKeys([]));
      api.listWebhooks().then(setWebhooks).catch(() => setWebhooks([]));
    }
  };

  useEffect(load, [canManage, owner]);

  const invite = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    try {
      await api.invite({ email: inviteEmail, role: inviteRole });
      setInviteEmail('');
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed');
    }
  };

  return (
    <main className="content">
      <h1>{t('dashboard')}</h1>
      <h2>{t('portfolio')}</h2>
      {portfolio.length === 0 ? (
        <p style={{ color: 'var(--muted)' }}>{t('journalsPlaceholder')}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>{t('journalTitle')}</th>
              <th>{t('lastAudit')}</th>
              <th>{t('meanScore')}</th>
              <th>{t('trend')}</th>
              <th>{t('gatewayFails')}</th>
              <th>{t('severeFindings')}</th>
              <th>{t('openActionsCol')}</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {portfolio.map(row => (
              <tr key={row.journalId}>
                <td><Link to={`/journals/${row.journalId}`}>{row.title ?? row.journalId.slice(0, 8)}</Link></td>
                <td>{row.lastAuditAt?.slice(0, 10) ?? '—'}</td>
                <td>{row.meanScore ?? '—'}</td>
                <td style={{ color: trendColor[row.trend] ?? '#333' }}>{trendGlyph[row.trend] ?? row.trend}</td>
                <td>{row.gatewayFails}</td>
                <td>{row.openSevereFindings}</td>
                <td>{row.openActions}</td>
                <td><Link to={`/journals/${row.journalId}/dashboard`}>{t('journalDashboard')}</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canManage && (
        <>
          <h2>{t('users')}</h2>
          <table>
            <thead>
              <tr>
                <th>{t('email')}</th>
                <th>{t('displayName')}</th>
                <th>{t('role')}</th>
                <th>{t('status')}</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.email}</td>
                  <td>{u.displayName}</td>
                  <td>{u.role}</td>
                  <td>{u.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {user?.role === 'OWNER' && (
            <form onSubmit={invite} style={{ marginTop: '1rem', gridTemplateColumns: '1fr auto auto', display: 'grid' }}>
              <input
                type="email"
                placeholder={t('email')}
                value={inviteEmail}
                onChange={(e) => setInviteEmail(e.target.value)}
                required
              />
              <select value={inviteRole} onChange={(e) => setInviteRole(e.target.value)}>
                <option value="ANALYST">ANALYST</option>
                <option value="VIEWER">VIEWER</option>
                <option value="OWNER">OWNER</option>
              </select>
              <button type="submit">{t('invite')}</button>
            </form>
          )}
          {error && <p className="error">{error}</p>}
        </>
      )}
      {owner && (
        <>
          <h2>{t('apiKeys')}</h2>
          {newSecret && (
            <p className="card" style={{ borderInlineStart: '4px solid #a06a00' }}>
              {t('keyShownOnce')}: <code>{newSecret}</code>
            </p>
          )}
          <table>
            <thead>
              <tr><th>{t('name')}</th><th>{t('scopes')}</th><th>{t('rateLimit')}</th><th>{t('status')}</th><th></th></tr>
            </thead>
            <tbody>
              {keys.map(k => (
                <tr key={k.id} style={{ opacity: k.revokedAt ? 0.5 : 1 }}>
                  <td>{k.name} <code className="secondary">{k.prefix}</code></td>
                  <td><code>{k.scopes}</code></td>
                  <td>{k.rateLimitPerMinute}/min</td>
                  <td>{k.revokedAt ? t('revoked') : t('activeLabel')}</td>
                  <td>
                    {!k.revokedAt && (
                      <button className="secondary" onClick={async () => {
                        await api.revokeApiKey(k.id);
                        load();
                      }}>{t('revoke')}</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <form
            style={{ display: 'grid', gridTemplateColumns: '1fr auto auto', gap: 8, marginTop: 8 }}
            onSubmit={async e => {
              e.preventDefault();
              const created = await api.createApiKey({
                name: keyName,
                scopes: keyWrite ? ['read', 'write'] : ['read'],
              });
              setNewSecret(created.secret ?? null);
              setKeyName('');
              load();
            }}
          >
            <input placeholder={t('name')} value={keyName} required
                   onChange={e => setKeyName(e.target.value)} />
            <label style={{ alignSelf: 'center' }}>
              <input type="checkbox" checked={keyWrite}
                     onChange={e => setKeyWrite(e.target.checked)} /> write
            </label>
            <button type="submit">{t('createKey')}</button>
          </form>

          <h2>{t('webhooks')}</h2>
          {webhookSecret && (
            <p className="card" style={{ borderInlineStart: '4px solid #a06a00' }}>
              {t('webhookSecretShownOnce')}: <code>{webhookSecret}</code>
            </p>
          )}
          <table>
            <thead>
              <tr><th>URL</th><th>{t('events')}</th><th>{t('lastDelivery')}</th><th></th></tr>
            </thead>
            <tbody>
              {webhooks.map(w => (
                <tr key={w.id} style={{ opacity: w.active ? 1 : 0.5 }}>
                  <td>{w.url}</td>
                  <td><code>{w.events}</code></td>
                  <td>{w.lastStatus ?? '—'} {w.lastDeliveryAt ? `· ${w.lastDeliveryAt.slice(0, 16)}` : ''}</td>
                  <td>
                    {w.active && (
                      <button className="secondary" onClick={async () => {
                        await api.deactivateWebhook(w.id);
                        load();
                      }}>{t('deactivate')}</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <form
            style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 8, marginTop: 8 }}
            onSubmit={async e => {
              e.preventDefault();
              const created = await api.createWebhook({
                url: webhookUrl,
                events: ['audit.completed', 'finding.critical'],
              });
              setWebhookSecret(created.secret);
              setWebhookUrl('');
              load();
            }}
          >
            <input placeholder="https://…" value={webhookUrl} required
                   onChange={e => setWebhookUrl(e.target.value)} />
            <button type="submit">{t('addWebhook')}</button>
          </form>
          <p><Link to="/admin">{t('adminConsole')}</Link></p>
        </>
      )}
    </main>
  );
}
