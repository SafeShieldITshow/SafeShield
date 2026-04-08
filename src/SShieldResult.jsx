import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './SShieldResult.css';

const EVIDENCE_ITEMS = [
    { id: 'e1', icon: '🎙️', title: '음성녹음',   desc: '위협 발언을 날짜·시간과 함께 녹음 보관' },
    { id: 'e2', icon: '🤕', title: '상해 사진',   desc: '신체 상해 사진 및 병원 진단서 확보' },
    { id: 'e3', icon: '💬', title: '메시지 캡처', desc: '카카오톡·SNS 등 위협 메시지 스크린샷' },
    { id: 'e4', icon: '👥', title: '목격자 진술', desc: '목격한 학생 또는 교사의 연락처 확보' },
];

const SShieldResult = () => {
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);
    const [checked, setChecked] = useState({});
    const navigate = useNavigate();

    const toggleCheck = (id) => setChecked(prev => ({ ...prev, [id]: !prev[id] }));
    const checkedCount = Object.values(checked).filter(Boolean).length;

    const score = 8.5;
    const cx = 80, cy = 90, r = 60;
    const angle = (score / 10) * 180;
    const rad = (Math.PI * angle) / 180;
    const x = cx + r * Math.cos(Math.PI - rad);
    const y = cy - r * Math.sin(Math.PI - rad);

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
                            <div className="ss-menu-circle">
                                <span></span><span></span><span></span>
                            </div>
                        </div>
                    )}
                    <div className={`ss-menu-content ${isMenuOpen ? 'visible' : ''}`}>
                        <nav className="ss-nav-list">
                            <div className="ss-nav-item active">
                                <span className="ss-emoji-icon">📊</span>
                                <span className="ss-nav-text">분석결과</span>
                            </div>
                            <div className="ss-nav-item" onClick={() => navigate('/mypage')}>
                                <span className="ss-emoji-icon">👤</span>
                                <span className="ss-nav-text">마이페이지</span>
                            </div>
                            <div className="ss-nav-item" onClick={() => navigate('/chat')}>
                                <span className="ss-emoji-icon">💬</span>
                                <span className="ss-nav-text">상담</span>
                            </div>
                        </nav>
                        <div className="ss-logout-section">
                            <div className="ss-divider"></div>
                            <button className="ss-logout-btn" onClick={() => setShowConfirm(true)}>
                                로그아웃
                            </button>
                        </div>
                    </div>
                </div>
            </aside>

            <main className="ss-main">
                <header className="ss-header">
                    <div className="ss-logo">S-<span className="logo-accent">Shield</span></div>
                    <button className="ss-back-btn" onClick={() => navigate('/chat')}>
                        채팅으로 돌아가기
                    </button>
                </header>

                <div className="ss-content-scroll">
                    <div className="ss-container">

                        <h1 className="ss-report-title">S-Shield 분석 결과 리포트</h1>

                        {/* AI 한 줄 요약 */}
                        <div className="ss-summary-card">
                            <span className="ss-summary-badge">AI 요약</span>
                            <p className="ss-summary-text">
                                신체 폭력과 언어 폭력이 복합적으로 확인됩니다. 지속적인 피해 패턴이 감지되어
                                즉각적인 신고 및 전문가 상담이 권고됩니다.
                            </p>
                        </div>

                        {/* 위험도 */}
                        <div className="ss-card ss-risk-card">
                            <div className="ss-gauge-container">
                                <svg viewBox="0 0 160 130" width="160" height="130">
                                    <path d="M 20 90 A 60 60 0 0 1 140 90" stroke="#3A3A3A" strokeWidth="12" fill="none" strokeLinecap="round" />
                                    <path d={`M 20 90 A 60 60 0 0 1 ${x} ${y}`} stroke="#7e74f0" strokeWidth="12" fill="none" strokeLinecap="round" />
                                    <polygon points={`${x},${y} ${x-5},${y+5} ${x+5},${y+5}`} fill="#7e74f0" />
                                    <circle cx="80" cy="90" r="4" fill="#FEFEFE" />
                                    <text x="80" y="115" textAnchor="middle" fill="#FEFEFE" fontSize="16" fontWeight="900">
                                        8.5<tspan fontSize="10" fill="#888">/10</tspan>
                                    </text>
                                    <text x="80" y="128" textAnchor="middle" fill="#7e74f0" fontSize="11" fontWeight="700">높음</text>
                                </svg>
                            </div>
                            <div>
                                <p className="ss-danger">※ 위험도 높음</p>
                                <p className="ss-risk-desc">
                                    신체적 위협과 지속적 괴롭힘이 복합적으로 확인되어 즉각적인 법률 조치가 권고됩니다.
                                </p>
                            </div>
                        </div>

                        {/* 상황 분석 */}
                        <h2 className="ss-sub-title">상황 분석</h2>
                        <div className="ss-grid">
                            <div className="ss-card">
                                <p className="ss-label">법률 매칭 분석</p>
                                <div className="ss-law-list">
                                    {[
                                        '학교폭력예방 및 대책에 관한 법률 제2조',
                                        '형법 제283조',
                                        '형법 제260조 또는 형법 제257조',
                                    ].map((l, i) => (
                                        <div className="ss-law-item" key={i}>
                                            <p>{l}</p>
                                            <div className="ss-bar-bg">
                                                <div className="ss-bar-fill" style={{ width: i===0?'88%':i===1?'73%':'57%' }} />
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            </div>

                            <div className="ss-card">
                                <p className="ss-label">예상 조치 및 징계</p>
                                <svg viewBox="0 0 280 85">
                                    <defs>
                                        <linearGradient id="grad" x1="0%" y1="0%" x2="100%">
                                            <stop offset="0%" stopColor="#3a3050"/>
                                            <stop offset="100%" stopColor="#5f52d0"/>
                                        </linearGradient>
                                    </defs>
                                    <rect x="15" y="5" width="186" height="50" fill="url(#grad)" />
                                    <line x1="15" y1="30" x2="265" y2="30" stroke="rgba(255,255,255,0.45)" strokeWidth="0.8" />
                                    {[15,46,77,108,139,170,201,232,263].map((px,i) => (
                                        <line key={i} x1={px} y1="5" x2={px} y2="55" stroke="rgba(255,255,255,0.45)" strokeWidth="0.8"/>
                                    ))}
                                    {['1호','2호','3호','4호','5호','6호','7호','8호','9호'].map((t,i) => (
                                        <text key={i} x={15+i*31} y="72" textAnchor="middle" fill="#fff" fontSize="10">{t}</text>
                                    ))}
                                </svg>
                            </div>
                        </div>

                        {/* 폭력 유형 */}
                        <h2 className="ss-sub-title">폭력 유형 분류</h2>
                        <div className="ss-card ss-center">
                            <p className="ss-ai">AI가 분석한 해당 폭력 유형</p>
                            <div className="ss-tags">
                                <span className="tag p">신체 폭력</span>
                                <span className="tag v">언어 폭력</span>
                                <span className="tag k">지속적 괴롭힘</span>
                            </div>
                        </div>

                        {/* 증거 수집 체크리스트 (UI만, 상태 없음) */}
                        <h2 className="ss-sub-title">증거 수집 가이드</h2>
                        <div className="ss-card">
                            <div className="ss-evidence-header">
                                <p className="ss-ai" style={{margin:0}}>아래 증거를 최대한 확보하세요</p>
                                <span className="ss-check-progress">{checkedCount} / {EVIDENCE_ITEMS.length} 완료</span>
                            </div>
                            <div className="ss-checklist">
                                {EVIDENCE_ITEMS.map((item) => (
                                    <div key={item.id} className={`check-item ${checked[item.id] ? 'checked' : ''}`} onClick={() => toggleCheck(item.id)}>
                                        <div className="check-box">
                                            {checked[item.id] && <span className="check-mark">✓</span>}
                                        </div>
                                        <div className="check-content">
                                            <strong>{item.icon} {item.title}</strong>
                                            <p>{item.desc}</p>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>

                        {/* 행동 제안 */}
                        <h2 className="ss-sub-title">행동 제안</h2>
                        <div className="ss-actions">
                            <a href="tel:117" className="ss-action-link">
                                <button>📞 117 학교 폭력 신고</button>
                            </a>
                            <button>👮 전문가 상담 신청</button>
                        </div>

                    </div>
                </div>
            </main>

            {showConfirm && (
                <div className="confirm-overlay" onClick={() => setShowConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>로그아웃 하시겠습니까?</p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={() => { localStorage.removeItem('token'); navigate('/'); }}>로그아웃</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default SShieldResult;
