import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

// UI in English and Arabic with full RTL mirroring (SRS NFR-I18N-1).
const resources = {
  en: {
    translation: {
      appName: 'JRAP',
      tagline: 'Journal Readiness Audit Platform',
      email: 'Email',
      password: 'Password',
      displayName: 'Your name',
      organisationName: 'Organisation name',
      totpCode: 'Two-factor code',
      login: 'Log in',
      register: 'Create organisation',
      logout: 'Log out',
      verifyEmailTitle: 'Verify your email',
      verifyEmailDone: 'Email verified. You can now log in.',
      acceptInvitationTitle: 'Accept your invitation',
      accept: 'Accept invitation',
      dashboard: 'Dashboard',
      users: 'Users',
      role: 'Role',
      status: 'Status',
      invite: 'Invite user',
      registerSuccess: 'Organisation created. Check your email to verify your account.',
      needAccount: 'Need an organisation account?',
      haveAccount: 'Already have an account?',
      journalsPlaceholder: 'Register journals and review their identity findings under Journals.',
      journals: 'Journals',
      registerJournal: 'Register journal',
      journalTitle: 'Title',
      publisher: 'Publisher',
      platform: 'Platform',
      source: 'Source',
      availability: 'Availability',
      identityBySource: 'Identity by source',
      findings: 'Findings',
      evidenceCount: '{{count}} evidence item(s)',
      language: 'العربية',
    },
  },
  ar: {
    translation: {
      appName: 'JRAP',
      tagline: 'منصة تدقيق جاهزية المجلات',
      email: 'البريد الإلكتروني',
      password: 'كلمة المرور',
      displayName: 'اسمك',
      organisationName: 'اسم المؤسسة',
      totpCode: 'رمز التحقق الثنائي',
      login: 'تسجيل الدخول',
      register: 'إنشاء مؤسسة',
      logout: 'تسجيل الخروج',
      verifyEmailTitle: 'تأكيد البريد الإلكتروني',
      verifyEmailDone: 'تم تأكيد البريد الإلكتروني. يمكنك الآن تسجيل الدخول.',
      acceptInvitationTitle: 'قبول الدعوة',
      accept: 'قبول الدعوة',
      dashboard: 'لوحة التحكم',
      users: 'المستخدمون',
      role: 'الدور',
      status: 'الحالة',
      invite: 'دعوة مستخدم',
      registerSuccess: 'تم إنشاء المؤسسة. تحقق من بريدك الإلكتروني لتأكيد حسابك.',
      needAccount: 'تحتاج إلى حساب مؤسسة؟',
      haveAccount: 'لديك حساب بالفعل؟',
      journalsPlaceholder: 'سجّل المجلات وراجع نتائج فحص الهوية ضمن قسم المجلات.',
      journals: 'المجلات',
      registerJournal: 'تسجيل مجلة',
      journalTitle: 'العنوان',
      publisher: 'الناشر',
      platform: 'المنصة',
      source: 'المصدر',
      availability: 'التوفر',
      identityBySource: 'الهوية حسب المصدر',
      findings: 'النتائج',
      evidenceCount: '{{count}} عنصر إثبات',
      language: 'English',
    },
  },
};

i18n.use(initReactI18next).init({
  resources,
  lng: 'en',
  fallbackLng: 'en',
  interpolation: { escapeValue: false },
});

export function applyDirection(lang: string) {
  document.documentElement.lang = lang;
  document.documentElement.dir = lang === 'ar' ? 'rtl' : 'ltr';
}

export default i18n;
