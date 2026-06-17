import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import './SShieldMypage.css';
import { api, clearSession } from './api.js';
import SessionHistory from './SessionHistory.jsx';
import { ChatIcon, ReportIcon, UserIcon } from './NavIcons.jsx';
import { formatKoreanNumericDateTime } from './time.js';

const getRiskLevel = (score) => {
    if (score >= 7) return { label: `높음 ${score}`, cls: 'high' };
    if (score >= 4) return { label: `중간 ${score}`, cls: 'medium' };
    return { label: `낮음 ${score}`, cls: 'low' };
};

const SShieldMypage = () => {
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [showLogoutConfirm, setShowLogoutConfirm] = useState(false);
    const [showDeleteConfirm, setShowDeleteConfirm] = useState(null);
    const [showDeleteSessionConfirm, setShowDeleteSessionConfirm] = useState(null);
    const [stats, setStats] = useState(null);
    const [reports, setReports] = useState([]);
    const [sessions, setSessions] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const navigate = useNavigate();
    const location = useLocation();

    useEffect(() => {
        Promise.all([
            api.get('/users/stats'),
            api.get('/reports'),
            api.get('/chat/sessions'),
        ])
            .then(([s, r, sessionItems]) => {
                setStats(s);
                setReports(r);
                setSessions(sessionItems);
            })
            .catch((e) => setError(e.message))
            .finally(() => setLoading(false));
    }, []);

    useEffect(() => {
        if (location.hash !== '#session-history' || loading) return;
        document.getElementById('session-history')?.scrollIntoView({ behavior: 'smooth' });
    }, [loading, location.hash]);

    const handleDelete = async (id) => {
        try {
            await api.del(`/reports/${id}`);
            setReports((prev) => prev.filter((report) => report.id !== id));
            setStats((prev) => prev ? { ...prev, reports_count: Math.max(0, (prev.reports_count ?? 1) - 1) } : prev);
        } catch (e) {
            alert(e.message);
        }
        setShowDeleteConfirm(null);
    };

    const handleDeleteSession = async (session) => {
        const id = session?.session_id;
        if (!id) return;
        try {
            await api.del(`/chat/sessions/${id}`);
            setSessions((prev) => prev.filter((item) => item.session_id !== id));
            setReports((prev) => prev.filter((report) => report.session_id !== id));
            setStats((prev) => prev ? {
                ...prev,
                sessions_count: Math.max(0, (prev.sessions_count ?? 1) - 1),
                reports_count: Math.max(0, (prev.reports_count ?? 0) - reports.filter((report) => report.session_id === id).length),
            } : prev);
        } catch (e) {
            alert(e.message);
        }
        setShowDeleteSessionConfirm(null);
    };

    const logout = () => {
        clearSession();
        navigate('/', { replace: true });
    };

    const avgRisk = reports.length
        ? (reports.reduce((sum, report) => sum + report.risk_score, 0) / reports.length).toFixed(1)
        : '-';

    const topTag = (() => {
        const freq = {};
        reports.forEach((report) => (report.violence_types || []).forEach((tag) => {
            freq[tag] = (freq[tag] || 0) + 1;
        }));
        return Object.entries(freq).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '-';
    })();

    const formatDate = (iso) => formatKoreanNumericDateTime(iso);
    const startNewChat = () => navigate('/chat?new=1');
    const listedSessions = sessions;
    const listedReports = reports;

    return (
        <div className="wrapper">
            <aside
                className={`ss-sidebar ${isMenuOpen ? 'open' : ''}`}
                onMouseEnter={() => setIsMenuOpen(true)}
                onMouseLeave={() => setIsMenuOpen(false)}
            >
                <div className="ss-sidebar-container">
                    {!isMenuOpen && (
                        <div className="ss-menu-btn"><div className="ss-menu-circle"><span></span><span></span><span></span></div></div>
                    )}
                    <div className={`ss-menu-content ${isMenuOpen ? 'visible' : ''}`}>
                        <nav className="ss-nav-list">
                            <div className="ss-nav-item" onClick={() => navigate('/result')}><span className="ss-emoji-icon"><ReportIcon /></span><span className="ss-nav-text">분석 결과</span></div>
                            <div className="ss-nav-item active"><span className="ss-emoji-icon"><UserIcon /></span><span className="ss-nav-text">마이페이지</span></div>
                            <div className="ss-nav-item" onClick={startNewChat}><span className="ss-emoji-icon"><ChatIcon /></span><span className="ss-nav-text">상담</span></div>
                        </nav>
                        <SessionHistory sessions={sessions} />
                        <div className="ss-logout-section">
                            <div className="ss-divider"></div>
                            <button className="ss-side-action-btn" onClick={startNewChat}>새 상담</button>
                            <button className="ss-logout-btn" onClick={() => setShowLogoutConfirm(true)}>로그아웃</button>
                        </div>
                    </div>
                </div>
            </aside>

            <div className="container">
                <header className="header">
                    <div className="pageHeaderTop">
                        <span aria-hidden="true"></span>
                        <button
                            type="button"
                            className="logo logo-button"
                            onClick={startNewChat}
                            aria-label="새 상담 시작"
                            title="새 상담 시작"
                        >
                            S-<span className="logo-accent">Shield</span>
                        </button>
                        <div className="headerActions">
                            <button type="button" className="pageActionBtn" onClick={startNewChat}>새 상담</button>
                        </div>
                    </div>
                    <nav className="ss-mobile-nav" aria-label="모바일 메뉴">
                        <button onClick={startNewChat}>상담</button>
                        <button onClick={() => navigate('/result')}>결과</button>
                        <button className="active">마이</button>
                        <button onClick={() => document.getElementById('session-history')?.scrollIntoView({ behavior: 'smooth' })}>
                            기록
                        </button>
                        <button onClick={startNewChat}>새 상담</button>
                    </nav>
                </header>

                <main className="mainContent">
                    <section className="section">
                        <h2 className="label">마이페이지</h2>
                        {loading ? (
                            <p style={{ color: '#aaa' }}>불러오는 중...</p>
                        ) : error ? (
                            <p style={{ color: '#ff6b6b' }}>{error}</p>
                        ) : (
                            <>
                                <div className="profileBox">
                                    <div className="userInfo">
                                        <p className="userName">{stats?.name || stats?.username || '사용자'}</p>
                                        <p className="userEmail">{stats?.email || stats?.username}</p>
                                    </div>
                                    <div className="userStats">
                                        <p>전체 상담 수 : <span>[ {stats?.sessions_count ?? 0}건 ]</span></p>
                                        <p>리포트 수 : <span>[ {stats?.reports_count ?? 0}건 ]</span></p>
                                    </div>
                                </div>

                                <div className="insightsRow">
                                    <div className="insightCard">
                                        <div className="insightValue">{avgRisk}</div>
                                        <div className="insightLabel">평균 위험도</div>
                                    </div>
                                    <div className="insightCard">
                                        <div className="insightValue">{topTag}</div>
                                        <div className="insightLabel">가장 많은 유형</div>
                                    </div>
                                    <div className="insightCard">
                                        <div className="insightValue">{reports.length}</div>
                                        <div className="insightLabel">전체 리포트</div>
                                    </div>
                                </div>

                            </>
                        )}
                    </section>

                    <div className="mypage-history-grid">
                        <section className="section" id="session-history">
                            <div className="sectionHeader">
                                <h2 className="label">상담 기록</h2>
                                <button className="btnDetail" onClick={startNewChat}>새 상담</button>
                            </div>
                            <SessionHistory
                                sessions={listedSessions}
                                variant="page"
                                onDelete={(session) => setShowDeleteSessionConfirm(session)}
                            />
                        </section>

                        <section className="section">
                            <div className="sectionHeader">
                                <h2 className="label">리포트 기록</h2>
                            </div>

                            <div className="reportList">
                                {reports.length === 0 ? (
                                    <p style={{ color: '#aaa', textAlign: 'center', padding: '24px' }}>
                                        아직 리포트가 없습니다.
                                    </p>
                                ) : listedReports.map((item) => {
                                    const risk = getRiskLevel(item.risk_score);
                                    return (
                                        <div key={item.id} className="reportCard">
                                            <div className="reportText">
                                                <span className="date">{formatDate(item.created_at)}</span>
                                                <span className={`risk-badge ${risk.cls}`}>{risk.label}</span>
                                                <h3 className="title">{item.title}</h3>
                                                <div className="tags">
                                                    {(item.violence_types || []).map((tag) => (
                                                        <span key={tag} className="tag">{tag}</span>
                                                    ))}
                                                </div>
                                            </div>
                                            <div className="actions">
                                                <button className="btnDetail" onClick={() => navigate(`/result?id=${item.id}`)}>상세 보기</button>
                                                <button className="btnDelete" onClick={() => setShowDeleteConfirm(item.id)}>삭제</button>
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        </section>
                    </div>

                    <footer className="accountSettings">
                        <h3>계정 설정</h3>
                        <div className="settingButtons">
                            <span className="logout" onClick={() => setShowLogoutConfirm(true)}>로그아웃</span>
                        </div>
                    </footer>
                </main>
            </div>

            {showLogoutConfirm && (
                <div className="confirm-overlay" onClick={() => setShowLogoutConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>로그아웃하시겠습니까?</p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowLogoutConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={logout}>로그아웃</button>
                        </div>
                    </div>
                </div>
            )}

            {showDeleteConfirm && (
                <div className="confirm-overlay" onClick={() => setShowDeleteConfirm(null)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>리포트를 삭제하시겠습니까?<br />
                            <small style={{ color: 'var(--text-muted)', fontSize: '13px' }}>삭제된 리포트는 복구할 수 없습니다.</small>
                        </p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowDeleteConfirm(null)}>취소</button>
                            <button className="confirm-btn-ok" onClick={() => handleDelete(showDeleteConfirm)}>삭제</button>
                        </div>
                    </div>
                </div>
            )}

            {showDeleteSessionConfirm && (
                <div className="confirm-overlay" onClick={() => setShowDeleteSessionConfirm(null)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>상담 기록을 삭제하시겠습니까?<br />
                            <small style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
                                연결된 대화와 리포트도 함께 삭제되며 복구할 수 없습니다.
                            </small>
                        </p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowDeleteSessionConfirm(null)}>취소</button>
                            <button className="confirm-btn-ok" onClick={() => handleDeleteSession(showDeleteSessionConfirm)}>삭제</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default SShieldMypage;
