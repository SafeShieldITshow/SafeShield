import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './SShieldMypage.css';

const getRiskLevel = (score) => {
    if (score >= 7.5) return { label: `🔴 ${score}`, cls: 'high' };
    if (score >= 4.5) return { label: `🟡 ${score}`, cls: 'medium' };
    return { label: `🟢 ${score}`, cls: 'low' };
};

const reportData = [
    { id: 1, date: '2026.04.06 16:20', title: '학교 폭력 의심 상담 (신체 폭력 외 2건)', tags: ['신체 폭력', '언어 폭력'],  risk: 8.5 },
    { id: 2, date: '2026.03.24 13:45', title: '지속적 따돌림 상담',                       tags: ['언어 폭력', '사이버 폭력'], risk: 6.2 },
    { id: 3, date: '2026.03.04 09:34', title: '일회성 다툼 상담 (언어 폭력)',              tags: ['언어 폭력'],                risk: 3.4 },
];

const avgRisk = (reportData.reduce((s, r) => s + r.risk, 0) / reportData.length).toFixed(1);
const topTag  = (() => {
    const freq = {};
    reportData.forEach(r => r.tags.forEach(t => { freq[t] = (freq[t] || 0) + 1; }));
    return Object.entries(freq).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '–';
})();

const SShieldMypage = () => {
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [showLogoutConfirm, setShowLogoutConfirm] = useState(false);
    const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
    const navigate = useNavigate();

    return (
        <div className="wrapper">
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
                            <div className="ss-nav-item" onClick={() => navigate('/result')}>
                                <span className="ss-emoji-icon">📊</span>
                                <span className="ss-nav-text">분석결과</span>
                            </div>
                            <div className="ss-nav-item active">
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
                            <button className="ss-logout-btn" onClick={() => setShowLogoutConfirm(true)}>
                                로그아웃
                            </button>
                        </div>
                    </div>
                </div>
            </aside>

            <div className="container">
                <header className="header">
                    <h1 className="logo">S-<span className="logo-accent">Shield</span></h1>
                </header>

                <main className="mainContent">
                    <section className="section">
                        <h2 className="label">마이페이지</h2>
                        <div className="profileBox">
                            <div className="userInfo">
                                <p className="userName">장세은</p>
                                <p className="userEmail">s2468@e-mirim.hs.kr</p>
                            </div>
                            <div className="userStats">
                                <p>총 상담 건수 : <span>[ 22건 ]</span></p>
                                <p>리포트 건수 : <span>[ 16개 ]</span></p>
                            </div>
                        </div>

                        {/* 인사이트 통계 */}
                        <div className="insightsRow">
                            <div className="insightCard">
                                <div className="insightValue">{avgRisk}</div>
                                <div className="insightLabel">평균 위험도</div>
                            </div>
                            <div className="insightCard">
                                <div className="insightValue">{topTag}</div>
                                <div className="insightLabel">가장 많은 폭력 유형</div>
                            </div>
                            <div className="insightCard">
                                <div className="insightValue">{reportData.length}</div>
                                <div className="insightLabel">이번 달 상담</div>
                            </div>
                        </div>
                    </section>

                    <section className="section">
                        <div className="sectionHeader">
                            <h2 className="label">내 상담 및 리포트 기록</h2>
                            <span className="sortOrder">최신순 ∨</span>
                        </div>

                        <div className="reportList">
                            {reportData.map((item) => {
                                const risk = getRiskLevel(item.risk);
                                return (
                                    <div key={item.id} className="reportCard">
                                        <div className="reportText">
                                            <span className="date">{item.date}</span>
                                            <span className={`risk-badge ${risk.cls}`}>{risk.label}</span>
                                            <h3 className="title">{item.title}</h3>
                                            <div className="tags">
                                                {item.tags.map((tag, i) => (
                                                    <span key={i} className="tag">{tag}</span>
                                                ))}
                                            </div>
                                        </div>
                                        <div className="actions">
                                            <button className="btnDetail">리포트 상세 보기</button>
                                            <button className="btnDelete" onClick={() => setShowDeleteConfirm(true)}>
                                                리포트 삭제
                                            </button>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    </section>

                    <div className="pagination">
                        <span className="pageActive">1</span>
                        <span>2</span>
                        <span>3</span>
                        <span>4</span>
                        <span>5</span>
                        <span>&gt;</span>
                    </div>

                    <footer className="accountSettings">
                        <h3>계정 설정</h3>
                        <div className="settingButtons">
                            <span>비밀번호 변경</span>
                            <span className="logout" onClick={() => setShowLogoutConfirm(true)}>로그아웃</span>
                        </div>
                    </footer>
                </main>
            </div>

            {/* 로그아웃 확인 */}
            {showLogoutConfirm && (
                <div className="confirm-overlay" onClick={() => setShowLogoutConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>로그아웃 하시겠습니까?</p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowLogoutConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={() => navigate('/')}>로그아웃</button>
                        </div>
                    </div>
                </div>
            )}

            {/* 삭제 확인 */}
            {showDeleteConfirm && (
                <div className="confirm-overlay" onClick={() => setShowDeleteConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>리포트를 삭제하시겠습니까?<br/>
                            <small style={{color:'var(--text-muted)', fontSize:'13px'}}>삭제된 리포트는 복구할 수 없습니다.</small>
                        </p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowDeleteConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={() => setShowDeleteConfirm(false)}>삭제</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default SShieldMypage;
