import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import './SShieldResult.css';
import { api, clearSession } from './api.js';
import SessionHistory from './SessionHistory.jsx';

const EVIDENCE_MAP = {
    '음성 녹음': { icon: '녹음', desc: '위협, 욕설, 협박 발언을 날짜와 시간 정보가 남도록 보관하세요.' },
    '상해 사진': { icon: '사진', desc: '멍, 상처 등 피해 부위를 여러 각도에서 촬영하고 촬영 날짜를 남기세요.' },
    '병원 진단서': { icon: '진단', desc: '의사 진단서, 치료 기록, 약 처방 기록을 함께 보관하세요.' },
    '메시지 캡처': { icon: '캡처', desc: '카카오톡, 문자, DM, SNS 메시지는 보낸 사람과 시간이 보이게 캡처하세요.' },
    '게시물 스크린샷': { icon: '게시물', desc: '게시물 URL, 작성자, 댓글, 업로드 시간이 보이도록 저장하세요.' },
    '목격자 진술': { icon: '진술', desc: '목격한 친구, 선생님, 주변인의 이름과 연락 가능 정보를 정리하세요.' },
    '일지 작성': { icon: '기록', desc: '날짜, 장소, 가해자, 목격자, 발생 내용을 빠짐없이 적어두세요.' },
    '접속 기록': { icon: '기록', desc: '온라인 괴롭힘은 계정명, 링크, 접속 기록, 알림 내역을 보관하세요.' },
};

const DEFAULT_EVIDENCE_KEYS = ['음성 녹음', '메시지 캡처', '일지 작성', '목격자 진술'];
const MEASURES = ['서면사과', '접촉금지', '학교봉사', '사회봉사', '특별교육', '출석정지', '학급교체', '전학', '퇴학'];

const riskLevel = (score) => {
    if (score >= 7) return '높음';
    if (score >= 4) return '중간';
    return '낮음';
};

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

    useEffect(() => {
        const id = params.get('id');
        const request = id ? api.get(`/reports/${id}`) : api.get('/reports/latest');

        request
            .then((data) => {
                setReport(data);
                const keys = data.evidence_guide?.length ? data.evidence_guide : DEFAULT_EVIDENCE_KEYS;
                setEvidenceItems(keys.map((key, index) => ({
                    id: `e${index}`,
                    icon: EVIDENCE_MAP[key]?.icon ?? '증거',
                    title: key,
                    desc: EVIDENCE_MAP[key]?.desc ?? '피해 사실을 확인할 수 있는 자료를 원본 형태로 보관하세요.',
                })));
            })
            .catch((e) => {
                setReport(null);
                setError(e.message);
                setEvidenceItems(DEFAULT_EVIDENCE_KEYS.map((key, index) => ({
                    id: `e${index}`,
                    icon: EVIDENCE_MAP[key]?.icon ?? '증거',
                    title: key,
                    desc: EVIDENCE_MAP[key]?.desc ?? '',
                })));
            })
            .finally(() => setLoading(false));
    }, [params]);

    const toggleCheck = (id) => setChecked((prev) => ({ ...prev, [id]: !prev[id] }));
    const checkedCount = Object.values(checked).filter(Boolean).length;

    const score = report?.risk_score ?? 0;
    const cx = 80;
    const cy = 90;
    const r = 60;
    const angle = (score / 10) * 180;
    const rad = (Math.PI * angle) / 180;
    const x = cx + r * Math.cos(Math.PI - rad);
    const y = cy - r * Math.sin(Math.PI - rad);
    const lawWidths = (report?.law_relevance_scores ?? []).map((s) => Math.round(s * 100));
    const rawRange = report?.expected_measure_range ?? [0, 3];
    const mStart = Math.max(0, Math.min(MEASURES.length - 1, rawRange[0] ?? 0));
    const mEnd = Math.max(mStart, Math.min(MEASURES.length - 1, rawRange[1] ?? 3));
    const COL_W = 31;
    const measureX = 15;
    const measureWidth = (mEnd + 1) * COL_W;
    const measureRangeText = `${mStart + 1}호(${MEASURES[mStart]})부터 ${mEnd + 1}호(${MEASURES[mEnd]})까지 가능성이 있습니다.`;

    const logout = () => {
        clearSession();
        navigate('/', { replace: true });
    };

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
                            <div className="ss-nav-item active"><span className="ss-emoji-icon">R</span><span className="ss-nav-text">분석 결과</span></div>
                            <div className="ss-nav-item" onClick={() => navigate('/mypage')}><span className="ss-emoji-icon">M</span><span className="ss-nav-text">마이페이지</span></div>
                            <div className="ss-nav-item" onClick={() => navigate('/chat')}><span className="ss-emoji-icon">C</span><span className="ss-nav-text">상담</span></div>
                        </nav>
                        <SessionHistory />
                        <div className="ss-logout-section">
                            <div className="ss-divider"></div>
                            <button className="ss-logout-btn" onClick={() => setShowConfirm(true)}>로그아웃</button>
                        </div>
                    </div>
                </div>
            </aside>

            <main className="ss-main">
                <header className="ss-header">
                    <div className="ss-logo">S-<span className="logo-accent">Shield</span></div>
                    <nav className="ss-mobile-nav" aria-label="모바일 메뉴">
                        <button onClick={() => navigate('/chat')}>상담</button>
                        <button className="active">결과</button>
                        <button onClick={() => navigate('/mypage')}>마이</button>
                        <button onClick={() => navigate('/mypage#session-history')}>기록</button>
                    </nav>
                    <button className="ss-back-btn" onClick={() => navigate('/chat')}>상담으로 돌아가기</button>
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
                                <button className="ss-inline-btn" onClick={() => navigate('/chat')}>
                                    상담 시작하기
                                </button>
                            </div>
                        ) : (
                            <>
                                <div className="ss-summary-card">
                                    <span className="ss-summary-badge">{report.assessment_status || 'AI 요약'}</span>
                                    <p className="ss-summary-text">{report.summary}</p>
                                </div>

                                <div className="ss-grid">
                                    <div className="ss-card">
                                        <p className="ss-label">핵심 판단 근거</p>
                                        <ul className="ss-report-list">
                                            {(report.assessment_details || []).map((item) => (
                                                <li key={item}>{item}</li>
                                            ))}
                                        </ul>
                                    </div>
                                    <div className="ss-card">
                                        <p className="ss-label">우선 권장 조치</p>
                                        <ul className="ss-report-list">
                                            {(report.recommended_actions || []).map((item) => (
                                                <li key={item}>{item}</li>
                                            ))}
                                        </ul>
                                    </div>
                                </div>

                                <div className="ss-card ss-risk-card">
                                    <div className="ss-gauge-container">
                                        <svg viewBox="0 0 160 130" width="160" height="130">
                                            <path d="M 20 90 A 60 60 0 0 1 140 90" stroke="#3A3A3A" strokeWidth="12" fill="none" strokeLinecap="round" />
                                            <path d={`M 20 90 A 60 60 0 0 1 ${x} ${y}`} stroke="#7e74f0" strokeWidth="12" fill="none" strokeLinecap="round" />
                                            <circle cx="80" cy="90" r="4" fill="#FEFEFE" />
                                            <text x="80" y="115" textAnchor="middle" fill="#FEFEFE" fontSize="16" fontWeight="900">
                                                {score}<tspan fontSize="10" fill="#888">/10</tspan>
                                            </text>
                                            <text x="80" y="128" textAnchor="middle" fill="#7e74f0" fontSize="11" fontWeight="700">
                                                {riskLevel(score)}
                                            </text>
                                        </svg>
                                    </div>
                                    <div>
                                        <p className="ss-danger">위험도 {riskLevel(score)}</p>
                                        <p className="ss-risk-desc">분석된 유형: {(report.violence_types || []).join(', ') || '분류 정보 없음'}</p>
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">상황 분석</h2>
                                <div className="ss-grid">
                                    <div className="ss-card">
                                        <p className="ss-label">관련 법령</p>
                                        <div className="ss-law-list">
                                            {(report.matched_laws || []).map((law, i) => (
                                                <div className="ss-law-item" key={law}>
                                                    <p>{law}</p>
                                                    <div className="ss-bar-bg">
                                                        <div className="ss-bar-fill" style={{ width: `${lawWidths[i] ?? 50}%` }} />
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    </div>

                                    <div className="ss-card">
                                        <p className="ss-label">예상 조치 범위</p>
                                        <svg viewBox="0 0 280 85">
                                            <defs>
                                                <linearGradient id="grad" x1="0%" y1="0%" x2="100%">
                                                    <stop offset="0%" stopColor="#3a3050" />
                                                    <stop offset="100%" stopColor="#5f52d0" />
                                                </linearGradient>
                                            </defs>
                                            <rect x={measureX} y="5" width={measureWidth} height="50" fill="url(#grad)" />
                                            <line x1="15" y1="30" x2="265" y2="30" stroke="rgba(255,255,255,0.45)" strokeWidth="0.8" />
                                            {MEASURES.map((_, i) => (
                                                <line key={i} x1={15 + i * 31} y1="5" x2={15 + i * 31} y2="55" stroke="rgba(255,255,255,0.45)" strokeWidth="0.8" />
                                            ))}
                                            {MEASURES.map((measure, i) => (
                                                <text key={measure} x={15 + i * 31} y="72" textAnchor="middle" fill="#fff" fontSize="8">{i + 1}</text>
                                            ))}
                                        </svg>
                                        <p className="ss-risk-desc">{measureRangeText}</p>
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">폭력 유형 분류</h2>
                                <div className="ss-card ss-center">
                                    <p className="ss-ai">AI가 분석한 해당 유형</p>
                                    <div className="ss-tags">
                                        {(report.violence_types || []).map((type) => (
                                            <span key={type} className="tag p">{type}</span>
                                        ))}
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">증거 수집 가이드</h2>
                                <div className="ss-card">
                                    <div className="ss-evidence-header">
                                        <p className="ss-ai" style={{ margin: 0 }}>상황에 맞는 증거를 최대한 원본 형태로 보관하세요.</p>
                                        <span className="ss-check-progress">{checkedCount} / {evidenceItems.length} 완료</span>
                                    </div>
                                    <div className="ss-checklist">
                                        {evidenceItems.map((item) => (
                                            <div key={item.id} className={`check-item ${checked[item.id] ? 'checked' : ''}`} onClick={() => toggleCheck(item.id)}>
                                                <div className="check-box">{checked[item.id] && <span className="check-mark">✓</span>}</div>
                                                <div className="check-content"><strong>{item.icon} {item.title}</strong><p>{item.desc}</p></div>
                                            </div>
                                        ))}
                                    </div>
                                </div>

                                <h2 className="ss-sub-title">다음 행동</h2>
                                <div className="ss-actions">
                                    <a href="tel:117" className="ss-action-link"><button>117 학교폭력 신고</button></a>
                                    <button onClick={() => navigate('/chat')}>상담 이어가기</button>
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
