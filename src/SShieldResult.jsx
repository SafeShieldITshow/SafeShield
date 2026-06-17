import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import './SShieldResult.css';
import { api, clearSession, hasToken } from './api.js';
import SessionHistory from './SessionHistory.jsx';
import { ChatIcon, ReportIcon, UserIcon } from './NavIcons.jsx';
import { formatKoreanDateTime } from './time.js';

const EVIDENCE_MAP = {
    '음성 녹음': { icon: '녹음', desc: '위협, 욕설, 협박 발언을 날짜와 시간 정보가 남도록 보관하세요.' },
    '상해 사진': { icon: '사진', desc: '멍, 상처 등 피해 부위를 여러 각도에서 촬영하고 촬영 날짜를 남기세요.' },
    '병원 진단서': { icon: '진단', desc: '의사 진단서, 치료 기록, 약 처방 기록을 함께 보관하세요.' },
    '메시지 캡처': { icon: '캡처', desc: '카카오톡, 문자, DM, SNS 메시지는 보낸 사람과 시간이 보이게 캡처하세요.' },
    '게시물 스크린샷': { icon: '게시물', desc: '게시물 URL, 작성자, 댓글, 업로드 시간이 보이도록 저장하세요.' },
    '목격자 진술': { icon: '진술', desc: '목격한 친구, 선생님, 주변인의 이름과 연락 가능 정보를 정리하세요.' },
    '일지 작성': { icon: '기록', desc: '날짜, 장소, 가해자, 목격자, 발생 내용을 빠짐없이 적어두세요.' },
    '접속 기록': { icon: '기록', desc: '온라인 괴롭힘은 계정명, 링크, 접속 기록, 알림 내역을 보관하세요.' },
    '게시물 전체 화면 캡처': { icon: '게시물', desc: '본문, 사진, 댓글, 작성자, 게시 시간이 한 화면에 보이게 저장하세요.' },
    'URL·작성자·게시시간 기록': { icon: '링크', desc: '주소, 게시글 번호, 계정 아이디, 닉네임, 업로드 시간을 따로 적어두세요.' },
    '원본 이미지·댓글 백업': { icon: '원본', desc: '사진 원본, 댓글 흐름, 알림 화면을 삭제 전 별도 폴더에 보관하세요.' },
    '계정 프로필 캡처': { icon: '계정', desc: '상대 계정의 아이디, 닉네임, 프로필 사진, 소개글이 보이게 캡처하세요.' },
    '대화방 전체 캡처': { icon: '대화', desc: '앞뒤 맥락, 참여자 목록, 보낸 시간까지 보이도록 길게 저장하세요.' },
    '참여자·계정 정보 정리': { icon: '계정', desc: '참여자 이름, 닉네임, 계정 ID, 학교 관계를 한 줄씩 정리하세요.' },
    '원본 파일 백업': { icon: '원본', desc: '사진, 영상, 알림, 대화 내역은 삭제 전 원본 파일이나 내보내기 형태로 따로 보관하세요.' },
    '상처 사진 촬영': { icon: '사진', desc: '멍이나 상처를 가까이와 전체 위치 두 방식으로 찍고 촬영 날짜를 남기세요.' },
    '진단서·진료 기록': { icon: '진단', desc: '진단서, 진료비 영수증, 처방전, 치료 날짜를 함께 보관하세요.' },
    '목격자 이름과 연락처': { icon: '목격', desc: '본 사람의 이름, 반, 연락 가능 여부, 들은 말의 요지를 정리하세요.' },
    '사건 일지': { icon: '기록', desc: '언제, 어디서, 누가, 무엇을 했는지 시간순으로 짧게 적어두세요.' },
    '녹음 파일 원본': { icon: '녹음', desc: '파일을 편집하지 말고 원본 그대로 보관하고 녹음 날짜를 기록하세요.' },
    '음성·통화 파일 원본': { icon: '녹음', desc: '녹음이나 통화 파일은 편집하지 말고 원본과 생성 시간을 함께 보관하세요.' },
    '발언 직후 사건 메모': { icon: '메모', desc: '욕설·협박의 정확한 표현, 주변에 있던 사람, 직후 상황을 적어두세요.' },
    '대화 캡처': { icon: '캡처', desc: '보낸 사람, 시간, 앞뒤 대화 맥락이 같이 보이도록 저장하세요.' },
    '대화 내보내기 원본': { icon: '원본', desc: '대화방 내보내기 파일이나 원본 백업을 따로 보관하세요.' },
    '댓글·공유 범위 기록': { icon: '확산', desc: '댓글 흐름, 공유된 범위, 본 사람 수나 반응을 시간순으로 정리하세요.' },
    '삭제·신고 처리 기록': { icon: '처리', desc: '삭제 요청, 플랫폼 신고, 차단, 처리 결과 화면을 날짜와 함께 남기세요.' },
    '보건실·담임 기록': { icon: '학교', desc: '보건실 방문, 담임 공유, 학교에서 받은 안내 내용을 날짜별로 남기세요.' },
    '반복 상황 일지': { icon: '반복', desc: '따돌림이나 배제가 반복된 날짜와 장소를 표처럼 누적하세요.' },
    '대화방·초대 제외 화면': { icon: '단톡', desc: '초대 제외, 강퇴, 읽씹 강요 등 대화방 상황이 보이게 저장하세요.' },
    '담임 공유 기록': { icon: '공유', desc: '보호자나 담임에게 알린 날짜, 전달한 증거, 받은 답변을 남기세요.' },
    '대화·사진 원본 보관': { icon: '원본', desc: '민감한 자료는 수정하지 말고 보호자나 신뢰할 수 있는 어른과 보관하세요.' },
    '진단서·상담 기록': { icon: '상담', desc: '병원, 상담기관, 학교 상담 일시와 상담 내용을 정리하세요.' },
    '피해 직후 사건 메모': { icon: '메모', desc: '기억이 흐려지기 전에 장소, 행동, 말, 주변 사람을 적어두세요.' },
    '연락·방문 시간 기록': { icon: '시간', desc: '연락이나 찾아온 시간을 날짜별로 모아 반복성을 보여주세요.' },
    '통화·메시지 내역': { icon: '연락', desc: '부재중 전화, 문자, 메신저, 차단 내역을 시간순으로 보관하세요.' },
    '위치·동선 기록': { icon: '위치', desc: '학교, 집 앞, 학원 등 반복 접근 장소와 시간을 따로 정리하세요.' },
    '요구 메시지 캡처': { icon: '요구', desc: '돈이나 물건을 요구한 말, 협박 표현, 시간을 함께 캡처하세요.' },
    '송금·결제 내역': { icon: '송금', desc: '계좌이체, 결제, 현금 전달 날짜와 금액을 확인할 수 있게 보관하세요.' },
    '물건 피해 사진': { icon: '물건', desc: '빼앗기거나 망가진 물건의 사진, 구매 내역, 상태를 남기세요.' },
    '본인 행동 타임라인': { icon: '정리', desc: '언제, 어디서, 누구에게, 어떤 행동을 했는지 과장 없이 시간순으로 적으세요.' },
    '게시물·댓글 원본과 URL': { icon: '원본', desc: '본인이 올린 게시물, 댓글, 사진, URL, 게시 시간을 삭제 전후로 확인 가능하게 보관하세요.' },
    '삭제·수정 내역 기록': { icon: '삭제', desc: '삭제·수정한 날짜, 요청받은 경로, 삭제 완료 화면을 함께 남기세요.' },
    '발언 경위 메모': { icon: '경위', desc: '문제가 된 말의 정확한 표현, 전후 상황, 듣거나 본 사람을 분리해서 정리하세요.' },
    '대화·녹음 원본': { icon: '원본', desc: '대화나 녹음 파일은 편집하지 말고 원본과 생성 시간을 확인할 수 있게 보관하세요.' },
    '상대방 피해 확인 자료': { icon: '확인', desc: '상대방의 상처, 치료, 불안 호소 등 피해 정도를 학교·보호자에게 확인한 기록을 남기세요.' },
    '반환·변상 기록': { icon: '회복', desc: '돈이나 물건을 돌려준 날짜, 금액, 방식, 확인받은 내용을 기록하세요.' },
    '사과·피해 회복 기록': { icon: '회복', desc: '직접 압박하지 말고 보호자나 학교를 통해 전달한 사과·회복 의사를 기록하세요.' },
    '보호자·담임 상담 기록': { icon: '상담', desc: '보호자, 담임, 학교 담당자에게 알린 날짜와 안내받은 내용을 정리하세요.' },
    '재발 방지 계획': { icon: '계획', desc: '연락 중단, 게시물 삭제, 대화방 퇴장, 상담 참여 등 다시 하지 않기 위한 행동 계획을 적으세요.' },
};

const DEFAULT_EVIDENCE_KEYS = ['음성 녹음', '메시지 캡처', '일지 작성', '목격자 진술'];
const MEASURES = ['서면사과', '접촉금지', '학교봉사', '사회봉사', '특별교육', '출석정지', '학급교체', '전학', '퇴학'];
const TEMP_REPORT_STORAGE_KEY = 'ss_temp_report';
const TEMP_REPORT_UPDATED_EVENT = 'ss_temp_report_updated';

const readTemporaryReport = () => {
    try {
        const raw = sessionStorage.getItem(TEMP_REPORT_STORAGE_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
};

const EVIDENCE_GROUPS = {
    '메시지 캡처': 'capture',
    '게시물 스크린샷': 'capture',
    '게시물 전체 화면 캡처': 'capture',
    '대화방 전체 캡처': 'capture',
    '대화 캡처': 'capture',
    '대화방·초대 제외 화면': 'capture',
    '요구 메시지 캡처': 'capture',
    '계정 프로필 캡처': 'account',
    '참여자·계정 정보 정리': 'account',
    '원본 이미지·댓글 백업': 'original',
    '원본 파일 백업': 'original',
    '대화 내보내기 원본': 'original',
    '음성·통화 파일 원본': 'original',
    '대화·사진 원본 보관': 'original',
    '대화·녹음 원본': 'original',
    '댓글·공유 범위 기록': 'spread',
    '삭제·신고 처리 기록': 'process',
    '일지 작성': 'timeline',
    '사건 일지': 'timeline',
    '반복 상황 일지': 'timeline',
    '발언 직후 사건 메모': 'timeline',
    '피해 직후 사건 메모': 'timeline',
    '본인 행동 타임라인': 'timeline',
    '발언 경위 메모': 'timeline',
    '목격자 진술': 'witness',
    '목격자 이름과 연락처': 'witness',
    '병원 진단서': 'medical',
    '진단서·진료 기록': 'medical',
    '진단서·상담 기록': 'medical',
    '담임 공유 기록': 'school-share',
    '보호자·담임 상담 기록': 'school-share',
    '보건실·담임 기록': 'school-medical',
};

const evidenceTitle = (item) => (
    item.icon && !item.title.includes(item.icon)
        ? `${item.icon} ${item.title}`
        : item.title
);

const compactEvidenceKeys = (keys = []) => {
    const seenKeys = new Set();
    const seenGroups = new Set();

    return keys
        .filter(Boolean)
        .reduce((items, key) => {
            if (seenKeys.has(key)) return items;
            seenKeys.add(key);

            const group = EVIDENCE_GROUPS[key];
            if (group && seenGroups.has(group)) return items;
            if (group) seenGroups.add(group);

            items.push(key);
            return items;
        }, [])
        .slice(0, 6);
};

const isPerpetratorStatus = (status = '') => status.includes('가해') || status.includes('연루');

const evidenceFallbackDesc = (status = '') => (
    isPerpetratorStatus(status)
        ? '본인이 한 행동과 중단·삭제·사과·피해 회복 조치를 시간순으로 원본 형태로 정리하세요.'
        : '피해 사실을 확인할 수 있는 자료를 원본 형태로 보관하세요.'
);

const riskLevel = (score) => {
    if (score >= 7) return '높음';
    if (score >= 4) return '중간';
    return '낮음';
};

const riskClass = (score) => {
    if (score >= 7) return 'high';
    if (score >= 4) return 'medium';
    return 'low';
};

const detailValue = (details = [], label) => {
    const prefix = `${label}:`;
    const item = details.find((detail) => String(detail).startsWith(prefix));
    return item ? String(item).slice(prefix.length).trim() : '';
};

const ASSESSMENT_DETAIL_PRIORITY = [
    '핵심 상황:',
    '관계 판단:',
    '행위 유형:',
    '발생 양상:',
    '증거 수준:',
    '피해 영향:',
];

const compactReportLines = (items = [], limit = 4) => items
    .map((item) => String(item || '').trim())
    .filter(Boolean)
    .slice(0, limit);

const prioritizeAssessmentDetails = (details = []) => {
    const visible = details
        .map((item) => String(item || '').trim())
        .filter((item) => item
            && !item.startsWith('판단 상태:')
            && !item.startsWith('판단 신뢰도:')
            && !item.startsWith('예상 조치 근거:'));
    const prioritized = ASSESSMENT_DETAIL_PRIORITY
        .map((prefix) => visible.find((item) => item.startsWith(prefix)))
        .filter(Boolean);
    const remaining = visible.filter((item) => !prioritized.includes(item));
    return compactReportLines([...prioritized, ...remaining], 4);
};

const formatDate = (value) => formatKoreanDateTime(value);

const SShieldResult = () => {
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);
    const [checked, setChecked] = useState({});
    const [evidenceItems, setEvidenceItems] = useState([]);
    const [report, setReport] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const location = useLocation();
    const reportId = params.get('id');
    const sessionReportId = params.get('session');
    const temporaryMode = params.get('temporary') === '1';

    useEffect(() => {
        const id = reportId;
        const sessionId = sessionReportId;
        let cancelled = false;

        const applyReport = (data) => {
            setReport(data);
            const rawKeys = data.evidence_guide?.length ? data.evidence_guide : DEFAULT_EVIDENCE_KEYS;
            const keys = compactEvidenceKeys(rawKeys);
            const fallbackDesc = evidenceFallbackDesc(data.assessment_status);
            setEvidenceItems(keys.map((key, index) => ({
                id: `e${index}`,
                icon: EVIDENCE_MAP[key]?.icon ?? '증거',
                title: key,
                desc: EVIDENCE_MAP[key]?.desc ?? fallbackDesc,
            })));
        };

        if (temporaryMode) {
            const loadTemporaryReport = () => {
                const temporaryReport = readTemporaryReport() || location.state?.report || null;
                if (temporaryReport) {
                    applyReport({ ...temporaryReport, temporary: true });
                    setError('');
                } else {
                    setReport(null);
                    setError('임시 리포트를 찾을 수 없습니다. 채팅에서 리포트 보기 버튼으로 다시 열어 주세요.');
                }
                setLoading(false);
            };

            loadTemporaryReport();
            window.addEventListener(TEMP_REPORT_UPDATED_EVENT, loadTemporaryReport);
            const tempTimer = window.setInterval(() => {
                if (document.visibilityState === 'visible') loadTemporaryReport();
            }, 2000);
            return () => {
                cancelled = true;
                window.removeEventListener(TEMP_REPORT_UPDATED_EVENT, loadTemporaryReport);
                window.clearInterval(tempTimer);
            };
        }

        if (!hasToken()) {
            setReport(null);
            setError('저장된 리포트를 보려면 로그인이 필요합니다. 임시 상담에서는 채팅에서 생성된 리포트 보기 버튼으로 확인해 주세요.');
            setEvidenceItems(DEFAULT_EVIDENCE_KEYS.map((key, index) => ({
                id: `e${index}`,
                icon: EVIDENCE_MAP[key]?.icon ?? '증거',
                title: key,
                desc: EVIDENCE_MAP[key]?.desc ?? '',
            })));
            setLoading(false);
            return () => {
                cancelled = true;
            };
        }

        const loadReport = (initial = false) => {
            const request = id
                ? api.get(`/reports/${id}`)
                : sessionId
                    ? api.get(`/reports/session/${sessionId}/latest`).catch((e) => {
                        if (e.status === 404) return api.get('/reports/latest');
                        throw e;
                    })
                    : api.get('/reports/latest');
            if (initial) setLoading(true);
            return request
            .then((data) => {
                if (cancelled) return;
                setError('');
                applyReport(data);
            })
            .catch((e) => {
                if (cancelled || !initial) return;
                setReport(null);
                setError(e.message);
                setEvidenceItems(DEFAULT_EVIDENCE_KEYS.map((key, index) => ({
                    id: `e${index}`,
                    icon: EVIDENCE_MAP[key]?.icon ?? '증거',
                    title: key,
                    desc: EVIDENCE_MAP[key]?.desc ?? '',
                })));
            })
            .finally(() => {
                if (!cancelled && initial) setLoading(false);
            });
        };

        loadReport(true);
        const timer = window.setInterval(() => {
            if (document.visibilityState === 'visible') loadReport(false);
        }, 5000);

        return () => {
            cancelled = true;
            window.clearInterval(timer);
        };
    }, [reportId, sessionReportId, temporaryMode, location.state]);

    const toggleCheck = (id) => setChecked((prev) => ({ ...prev, [id]: !prev[id] }));
    const checkedCount = Object.values(checked).filter(Boolean).length;

    const score = report?.risk_score ?? 0;
    const rawRange = report?.expected_measure_range ?? [0, 3];
    const mStart = Math.max(0, Math.min(MEASURES.length - 1, rawRange[0] ?? 0));
    const mEnd = Math.max(mStart, Math.min(MEASURES.length - 1, rawRange[1] ?? 3));
    const measureRangeText = `${mStart + 1}호(${MEASURES[mStart]})부터 ${mEnd + 1}호(${MEASURES[mEnd]})까지 가능성이 있습니다.`;
    const isPerpetratorReport = isPerpetratorStatus(report?.assessment_status);
    const evidenceSectionTitle = isPerpetratorReport ? '사실관계 자료 정리 가이드' : '증거 수집 가이드';
    const evidenceSectionDesc = isPerpetratorReport
        ? '본인이 한 행동, 중단·삭제·사과·피해 회복 내역을 원본 형태로 정리하세요.'
        : '상황에 맞는 증거를 최대한 원본 형태로 보관하세요.';
    const reportCreatedAt = formatDate(report?.created_at);
    const typeTags = report?.violence_types?.length ? report.violence_types : ['분류 정보 없음'];
    const riskStatusClass = riskClass(score);
    const assessmentDetails = report?.assessment_details || [];
    const confidenceText = detailValue(assessmentDetails, '판단 신뢰도') || '추가 대화에 따라 달라질 수 있습니다.';
    const measureBasis = detailValue(assessmentDetails, '예상 조치 근거') || measureRangeText;
    const visibleAssessmentDetails = prioritizeAssessmentDetails(assessmentDetails);
    const visibleRecommendedActions = compactReportLines(report?.recommended_actions || [], 4);
    const primaryAction = visibleRecommendedActions[0] || '상담 내용을 조금 더 보강해 주세요.';
    const actionList = visibleRecommendedActions.length ? visibleRecommendedActions : [primaryAction];
    const riskPercent = Math.max(0, Math.min(100, Number(score || 0) * 10));

    const logout = () => {
        clearSession();
        navigate('/', { replace: true });
    };
    const reportChatPath = report?.session_id ? `/chat?session=${report.session_id}` : '/chat';
    const goToReportChat = () => navigate(reportChatPath);
    const startNewChat = () => navigate('/chat?new=1');

    return (
        <div className="ss-wrap">
            <aside
                className={`ss-sidebar ${isMenuOpen ? 'open' : ''}`}
                onMouseEnter={() => setIsMenuOpen(true)}
                onMouseLeave={() => setIsMenuOpen(false)}
            >
                <div className="ss-sidebar-container">
                    {!isMenuOpen && (
                        <div className="ss-menu-btn">
                            <div className="ss-menu-circle"><span></span><span></span><span></span></div>
                        </div>
                    )}
                    <div className={`ss-menu-content ${isMenuOpen ? 'visible' : ''}`}>
                        <nav className="ss-nav-list">
                            <div className="ss-nav-item active"><span className="ss-emoji-icon"><ReportIcon /></span><span className="ss-nav-text">분석 결과</span></div>
                            <div className="ss-nav-item" onClick={() => navigate('/mypage')}><span className="ss-emoji-icon"><UserIcon /></span><span className="ss-nav-text">마이페이지</span></div>
                            <div className="ss-nav-item" onClick={goToReportChat}><span className="ss-emoji-icon"><ChatIcon /></span><span className="ss-nav-text">상담</span></div>
                        </nav>
                        <SessionHistory />
                        <div className="ss-logout-section">
                            <div className="ss-divider"></div>
                            <button className="ss-side-action-btn" onClick={startNewChat}>새 상담</button>
                            <button className="ss-logout-btn" onClick={() => setShowConfirm(true)}>로그아웃</button>
                        </div>
                    </div>
                </div>
            </aside>

            <main className="ss-main">
                <header className="ss-header">
                    <div className="ss-page-topbar">
                        <span aria-hidden="true"></span>
                        <button
                            type="button"
                            className="ss-logo ss-logo-button"
                            onClick={startNewChat}
                            aria-label="새 상담 시작"
                            title="새 상담 시작"
                        >
                            S-<span className="logo-accent">Shield</span>
                        </button>
                        <div className="ss-header-actions">
                            <button type="button" className="ss-header-btn secondary" onClick={goToReportChat}>상담 이어가기</button>
                            <button type="button" className="ss-header-btn primary" onClick={startNewChat}>새 상담</button>
                        </div>
                    </div>
                    <nav className="ss-mobile-nav" aria-label="모바일 메뉴">
                        <button onClick={goToReportChat}>상담</button>
                        <button className="active">결과</button>
                        <button onClick={() => navigate('/mypage')}>마이</button>
                        <button onClick={() => navigate('/mypage#session-history')}>기록</button>
                        <button onClick={startNewChat}>새 상담</button>
                    </nav>
                </header>

                <div className="ss-content-scroll">
                    <div className="ss-container">
                        <h1 className="ss-report-title">S-Shield 분석 결과 리포트</h1>

                        {loading ? (
                            <p style={{ color: '#aaa', textAlign: 'center' }}>리포트를 불러오는 중...</p>
                        ) : !report ? (
                            <div className="ss-summary-card">
                                <p className="ss-summary-text">
                                    {error || '아직 생성된 리포트가 없습니다. 상담을 진행한 뒤 리포트를 생성해 주세요.'}
                                </p>
                                <button className="ss-inline-btn" onClick={startNewChat}>
                                    상담 시작하기
                                </button>
                            </div>
                        ) : (
                            <>
                                <section className="report-hero">
                                    <div className="report-hero-main">
                                        <span className="ss-summary-badge">{report.assessment_status || 'AI 요약'}</span>
                                        <h2>{report.title || '분석 결과 리포트'}</h2>
                                        <p>{report.summary}</p>
                                        {reportCreatedAt && <span className="report-date">생성일 {reportCreatedAt}</span>}
                                    </div>
                                    <div className={`report-risk-chip ${riskStatusClass}`}>
                                        <div className="report-risk-number">
                                            <strong>{score}</strong>
                                            <span>/10 · {riskLevel(score)}</span>
                                        </div>
                                        <div className="report-risk-meter" aria-hidden="true">
                                            <i style={{ width: `${riskPercent}%` }}></i>
                                        </div>
                                        <small>추가 설명에 따라 달라질 수 있습니다.</small>
                                    </div>
                                </section>

                                <section className="report-trust-panel" aria-label="리포트 이용 안내">
                                    <div>
                                        <span>판단 한계</span>
                                        <strong>AI 분석은 참고용입니다.</strong>
                                        <p>실제 처분과 법적 판단은 학교 조사, 증거, 담당 기관 판단에 따라 달라집니다.</p>
                                    </div>
                                    <div>
                                        <span>바로 도움받기</span>
                                        <strong>지금 안전이 걱정되면 먼저 사람에게 연결하세요.</strong>
                                        <p>몸이 다칠까 걱정되거나, 보복이 두렵거나, 혼자 있기 어려울 만큼 마음이 위험하게 느껴진다면 보호자·학교 담당자·117 또는 112에 바로 연결하세요.</p>
                                    </div>
                                    <div>
                                        <span>기록 관리</span>
                                        <strong>{report.temporary ? '임시 리포트입니다.' : '저장된 리포트입니다.'}</strong>
                                        <p>{report.temporary ? '로그인 전 리포트는 브라우저에 임시 보관됩니다.' : '마이페이지에서 연결된 상담 기록과 리포트를 삭제할 수 있습니다.'}</p>
                                    </div>
                                </section>

                                <div className="report-stat-grid compact">
                                    <div className="report-stat-card">
                                        <span>분류 유형</span>
                                        <strong>{typeTags.length}개</strong>
                                        <div className="mini-tags">
                                            {typeTags.map((type) => <em key={type}>{type}</em>)}
                                        </div>
                                    </div>
                                    <div className="report-stat-card">
                                        <span>판단 신뢰도</span>
                                        <strong>{confidenceText.split(' - ')[0]}</strong>
                                        <p>{confidenceText.split(' - ').slice(1).join(' - ') || '확인된 사실 기준으로 산정했습니다.'}</p>
                                    </div>
                                    <div className="report-stat-card">
                                        <span>자료 정리</span>
                                        <strong>{checkedCount}/{evidenceItems.length}</strong>
                                        <p>체크한 항목은 화면에서 바로 확인할 수 있습니다.</p>
                                    </div>
                                </div>

                                <section className="report-next-strip">
                                    <div>
                                        <span>먼저 볼 것</span>
                                        <strong>{primaryAction}</strong>
                                    </div>
                                    <button type="button" onClick={goToReportChat}>내용 더 보강하기</button>
                                </section>

                                <div className="report-main-grid assessment-action-grid">
                                    <section className="ss-card report-section balanced-report-section">
                                        <div className="report-section-head">
                                            <span>01</span>
                                            <div>
                                                <p className="ss-label">핵심 판단 근거</p>
                                                <small>대화에서 확인된 사실을 기준으로 정리했습니다.</small>
                                            </div>
                                        </div>
                                        <ul className="ss-report-list clean">
                                            {visibleAssessmentDetails.map((item) => (
                                                <li key={item}>{item}</li>
                                            ))}
                                        </ul>
                                    </section>

                                    <section className="ss-card report-section action-section balanced-report-section">
                                        <div className="report-section-head">
                                            <span>02</span>
                                            <div>
                                                <p className="ss-label">우선 권장 조치</p>
                                                <small>분석 기준으로 정리한 권장 순서입니다.</small>
                                            </div>
                                        </div>
                                        <ul className="ss-report-list clean">
                                            {actionList.map((item) => (
                                                <li key={item}>{item}</li>
                                            ))}
                                        </ul>
                                    </section>
                                </div>

                                <div className="report-main-grid">
                                    <section className="ss-card report-section">
                                        <div className="report-section-head">
                                            <span>03</span>
                                            <div>
                                                <p className="ss-label">관련 법령</p>
                                                <small>현재 사안과 연결되는 항목만 표시합니다.</small>
                                            </div>
                                        </div>
                                        <div className="ss-law-list refined">
                                            {(report.matched_laws || []).length ? (report.matched_laws || []).map((law, i) => (
                                                <div className="ss-law-item" key={law}>
                                                    <p>{law}</p>
                                                    <span>{Math.round((report.law_relevance_scores?.[i] ?? 0.5) * 100)}%</span>
                                                </div>
                                            )) : <p className="empty-note">관련 법령 정보가 아직 충분하지 않습니다.</p>}
                                        </div>
                                    </section>

                                    <section className="ss-card report-section measure-section">
                                        <div className="report-section-head">
                                            <span>04</span>
                                            <div>
                                                <p className="ss-label">예상 조치 범위</p>
                                                <small>{measureRangeText}</small>
                                            </div>
                                        </div>
                                        <p className="measure-basis-note">{measureBasis}</p>
                                        <div className="measure-steps">
                                            {MEASURES.map((measure, i) => (
                                                <div
                                                    key={measure}
                                                    className={`measure-step ${i <= mEnd ? 'active' : ''} ${i >= mStart && i <= mEnd ? 'in-range' : ''}`}
                                                >
                                                    <strong>{i + 1}</strong>
                                                    <span>{measure}</span>
                                                </div>
                                            ))}
                                        </div>
                                    </section>
                                </div>

                                <section className="ss-card report-section evidence-section">
                                    <div className="ss-evidence-header">
                                        <div>
                                            <p className="ss-label">{evidenceSectionTitle}</p>
                                            <p className="ss-ai" style={{ margin: 0 }}>{evidenceSectionDesc}</p>
                                        </div>
                                        <span className="ss-check-progress">{checkedCount} / {evidenceItems.length} 완료</span>
                                    </div>
                                    <div className="ss-checklist">
                                        {evidenceItems.map((item) => (
                                            <div key={item.id} className={`check-item ${checked[item.id] ? 'checked' : ''}`} onClick={() => toggleCheck(item.id)}>
                                                <div className="check-box">{checked[item.id] && <span className="check-mark">✓</span>}</div>
                                                <div className="check-content"><strong>{evidenceTitle(item)}</strong><p>{item.desc}</p></div>
                                            </div>
                                        ))}
                                    </div>
                                </section>

                                <div className="ss-actions report-actions">
                                    <a href="tel:117" className="ss-action-link"><button>117 학교폭력 신고</button></a>
                                    <button onClick={goToReportChat}>상담 이어가기</button>
                                    <button onClick={startNewChat}>새 상담</button>
                                </div>
                            </>
                        )}
                    </div>
                </div>
            </main>

            {showConfirm && (
                <div className="confirm-overlay" onClick={() => setShowConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>로그아웃하시겠습니까?</p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={logout}>로그아웃</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default SShieldResult;
