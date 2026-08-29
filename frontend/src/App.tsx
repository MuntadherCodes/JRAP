import { useTranslation } from 'react-i18next';
import { Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { clearSession, session } from './api';
import { applyDirection } from './i18n';
import AcceptInvitation from './pages/AcceptInvitation';
import Dashboard from './pages/Dashboard';
import AdminPage from './pages/AdminPage';
import AuditView from './pages/AuditView';
import JournalDashboard from './pages/JournalDashboard';
import JournalDetail from './pages/JournalDetail';
import Journals from './pages/Journals';
import Login from './pages/Login';
import Register from './pages/Register';
import ReportView from './pages/ReportView';
import ReviewQueue from './pages/ReviewQueue';
import VerifyEmail from './pages/VerifyEmail';

export default function App() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const authed = session().accessToken !== null;

  const toggleLanguage = () => {
    const next = i18n.language === 'ar' ? 'en' : 'ar';
    i18n.changeLanguage(next);
    applyDirection(next);
  };

  return (
    <>
      <header className="topbar">
        <strong>
          {t('appName')} <span style={{ color: 'var(--muted)', fontWeight: 400 }}>{t('tagline')}</span>
        </strong>
        <nav style={{ display: 'flex', gap: '0.6rem' }}>
          {authed && (
            <button className="secondary" onClick={() => navigate('/journals')}>
              {t('journals')}
            </button>
          )}
          <button className="secondary" onClick={toggleLanguage}>
            {t('language')}
          </button>
          {authed && (
            <button
              className="secondary"
              onClick={() => {
                clearSession();
                navigate('/login');
              }}
            >
              {t('logout')}
            </button>
          )}
        </nav>
      </header>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/verify-email" element={<VerifyEmail />} />
        <Route path="/accept-invitation" element={<AcceptInvitation />} />
        <Route path="/journals" element={authed ? <Journals /> : <Navigate to="/login" replace />} />
        <Route path="/journals/:id" element={authed ? <JournalDetail /> : <Navigate to="/login" replace />} />
        <Route path="/journals/:id/dashboard" element={authed ? <JournalDashboard /> : <Navigate to="/login" replace />} />
        <Route path="/admin" element={authed ? <AdminPage /> : <Navigate to="/login" replace />} />
        <Route path="/audits/:id" element={authed ? <AuditView /> : <Navigate to="/login" replace />} />
        <Route path="/audits/:id/review" element={authed ? <ReviewQueue /> : <Navigate to="/login" replace />} />
        <Route path="/reports/:id" element={authed ? <ReportView /> : <Navigate to="/login" replace />} />
        <Route path="/" element={authed ? <Dashboard /> : <Navigate to="/login" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  );
}
