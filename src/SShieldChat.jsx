import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import './SShieldChat.css';

const EXAMPLE_QUESTIONS = [
    '친구에게 지속적으로 맞고 있어요',
    'SNS에서 욕설을 당하고 있어요',
    '학교에서 따돌림을 당하고 있어요',
];

const SShieldChat = () => {
    const [isMenuOpen, setIsMenuOpen] = useState(false);
    const [isLoggingOut, setIsLoggingOut] = useState(false);
    const [showConfirm, setShowConfirm] = useState(false);
    const [messages, setMessages] = useState([
        { id: 1, type: 'user', text: '안녕하세요. 학교 폭력 상황 분석을 부탁드립니다.', time: '오후 2:30' },
        { id: 2, type: 'ai',   text: '안녕하세요. S-Shield 법률 AI입니다.\n어떤 상황인지 자세히 말씀해 주세요.', time: '오후 2:30' },
    ]);
    const [input, setInput] = useState('');
    const scrollRef = useRef();
    const navigate = useNavigate();

    useEffect(() => {
        if (scrollRef.current) {
            scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
        }
    }, [messages]);

    const handleSend = () => {
        if (!input.trim()) return;
        const t = new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' });
        const userMsg = { id: Date.now(), type: 'user', text: input, time: t };
        setMessages(prev => [...prev, userMsg]);
        setInput('');

        setTimeout(() => {
            setMessages(prev => [...prev, {
                id: Date.now() + 1,
                type: 'ai',
                text: '현재 입력하신 내용을 바탕으로 관련 법령을 분석 중입니다. 잠시만 기다려 주세요.',
                time: new Date().toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' }),
            }]);
        }, 1200);
    };

    const handleLogout = () => {
        setIsLoggingOut(true);
        setTimeout(() => navigate('/'), 1500);
    };

    return (
        <div className={`ss-chat-root ${isLoggingOut ? 'logging-out' : ''}`}>

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
                            <div className="ss-nav-item" onClick={() => navigate('/mypage')}>
                                <span className="ss-emoji-icon">👤</span>
                                <span className="ss-nav-text">마이페이지</span>
                            </div>
                            <div className="ss-nav-item active">
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

            <main className="ss-chat-main">
                <header className="ss-chat-header">
                    <div className="ss-chat-logo">
                        S-<span className="logo-accent">Shield</span>
                    </div>
                    <div className="ss-header-line"></div>
                </header>

                <div className="ss-chat-content" ref={scrollRef}>
                    {messages.map((msg) => (
                        <div key={msg.id} className={`ss-msg-row ${msg.type}`}>
                            <div className="ss-msg-col">
                                <div className="ss-msg-bubble">
                                    {msg.text.split('\n').map((line, i, arr) => (
                                        <span key={i}>{line}{i < arr.length - 1 && <br/>}</span>
                                    ))}
                                </div>
                                <span className="msg-time">{msg.time}</span>
                            </div>
                        </div>
                    ))}
                </div>

                <footer className="ss-chat-footer">
                    {/* 예시 질문 칩 */}
                    <div className="example-chips">
                        {EXAMPLE_QUESTIONS.map((q, i) => (
                            <button key={i} className="example-chip">{q}</button>
                        ))}
                    </div>

                    {/* 리포트 CTA */}
                    <div className="chat-cta">
                        <p>✅ 충분한 정보가 수집됐어요. 분석 리포트를 확인해보세요.</p>
                        <button className="chat-cta-btn" onClick={() => navigate('/result')}>
                            리포트 보기
                        </button>
                    </div>

                    <div className="ss-input-box">
                        <input
                            type="text"
                            placeholder="상황을 입력해 주세요."
                            value={input}
                            onChange={(e) => setInput(e.target.value)}
                            onKeyPress={(e) => e.key === 'Enter' && handleSend()}
                        />
                        <button className="ss-send-btn" onClick={handleSend}>보내기</button>
                    </div>
                </footer>
            </main>

            {isLoggingOut && (
                <div className="logout-overlay">
                    <div className="logout-spinner"></div>
                    <p>안전하게 로그아웃 중입니다...</p>
                </div>
            )}

            {showConfirm && (
                <div className="confirm-overlay" onClick={() => setShowConfirm(false)}>
                    <div className="confirm-box" onClick={(e) => e.stopPropagation()}>
                        <p>로그아웃 하시겠습니까?</p>
                        <div className="confirm-btns">
                            <button className="confirm-btn-cancel" onClick={() => setShowConfirm(false)}>취소</button>
                            <button className="confirm-btn-ok" onClick={() => { setShowConfirm(false); handleLogout(); }}>로그아웃</button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default SShieldChat;
