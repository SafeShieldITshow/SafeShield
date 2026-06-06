import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import './SShieldLogin.css';
import { API_BASE_URL, api, clearSession, setToken } from './api.js';

function SShieldLogin() {
    const [activeTab, setActiveTab] = useState('login');
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    const [name, setName] = useState('');
    const [email, setEmail] = useState('');
    const [error, setError] = useState('');
    const [success, setSuccess] = useState('');
    const [loading, setLoading] = useState(false);
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const passwordRef = useRef(null);

    const isSignup = activeTab === 'signup';

    useEffect(() => {
        if (params.get('oauth_error')) {
            setError('Google 로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.');
        }
    }, [params]);

    const handleSubmit = async () => {
        if (!username.trim() || !password.trim()) {
            setError('아이디와 비밀번호를 입력해 주세요.');
            return;
        }
        if (isSignup && !name.trim()) {
            setError('이름을 입력해 주세요.');
            return;
        }
        if (isSignup && password.length < 8) {
            setError('비밀번호는 8자 이상 입력해 주세요.');
            return;
        }

        setLoading(true);
        setError('');
        setSuccess('');

        try {
            if (isSignup) {
                await api.post('/auth/signup', {
                    username: username.trim(),
                    password,
                    name: name.trim(),
                    email: email.trim(),
                });
                clearSession();
                setPassword('');
                setName('');
                setEmail('');
                setActiveTab('login');
                setSuccess('회원가입이 완료되었습니다. 가입한 아이디로 로그인해 주세요.');
                window.setTimeout(() => passwordRef.current?.focus(), 280);
                return;
            }

            const data = await api.post('/auth/login', { username: username.trim(), password });
            setToken(data.token);
            localStorage.setItem('ss_username', data.username || username.trim());
            localStorage.setItem('ss_name', data.name || data.username || username.trim());
            navigate('/chat', { replace: true });
        } catch (e) {
            setError(e.message);
        } finally {
            setLoading(false);
        }
    };

    const switchTab = (tab) => {
        setActiveTab(tab);
        setError('');
        setSuccess('');
    };

    return (
        <div className="login-container">
            <div className="top-logo clickable" onClick={() => navigate('/')}>
                S-<span className="logo-accent">Shield</span>
            </div>

            <div className="form-wrapper animated-entrance">
                <div className="login-heading">
                    <span className="login-kicker">SAFE LEGAL GUIDE</span>
                    <h1>{isSignup ? '계정을 만들고 상담을 시작하세요' : '다시 만나서 반가워요'}</h1>
                    <p>상담 기록과 분석 리포트는 계정에 안전하게 저장됩니다.</p>
                </div>

                <div className="tab-group-container">
                    <div className={`tab-active-bar ${isSignup ? 'pos-left' : 'pos-right'}`}></div>
                    <button
                        className={`tab-btn-item ${isSignup ? 'selected' : ''}`}
                        onClick={() => switchTab('signup')}
                    >
                        회원가입
                    </button>
                    <button
                        className={`tab-btn-item ${!isSignup ? 'selected' : ''}`}
                        onClick={() => switchTab('login')}
                    >
                        로그인
                    </button>
                </div>

                <div className={`signup-fields ${isSignup ? 'visible' : ''}`} aria-hidden={!isSignup}>
                        <div className="input-group-container">
                            <label className="input-label">이름</label>
                            <input
                                type="text"
                                placeholder="이름을 입력하세요"
                                className="clean-purple-input"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                disabled={!isSignup}
                            />
                        </div>
                        <div className="input-group-container">
                            <label className="input-label">이메일</label>
                            <input
                                type="email"
                                placeholder="선택 입력"
                                className="clean-purple-input"
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                                disabled={!isSignup}
                            />
                        </div>
                </div>

                <div className="input-group-container">
                    <label className="input-label">아이디</label>
                    <input
                        type="text"
                        placeholder="아이디를 입력하세요"
                        className="clean-purple-input"
                        value={username}
                        onChange={(e) => setUsername(e.target.value)}
                    />
                </div>

                <div className="input-group-container last-input">
                    <label className="input-label">비밀번호</label>
                    <input
                        ref={passwordRef}
                        type="password"
                        placeholder="비밀번호를 입력하세요"
                        className="clean-purple-input"
                        value={password}
                        onChange={(e) => setPassword(e.target.value)}
                        onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                    />
                </div>

                {error && (
                    <p className="form-message error" role="alert">
                        {error}
                    </p>
                )}
                {success && (
                    <p className="form-message success" role="status">
                        {success}
                    </p>
                )}

                <button className="main-action-btn" onClick={handleSubmit} disabled={loading}>
                    {loading ? '처리 중...' : isSignup ? '회원가입' : '로그인'}
                </button>

                <div className="divider"><span>또는</span></div>

                <button
                    className="google-login-btn"
                    onClick={() => {
                        window.location.href = `${API_BASE_URL}/oauth2/authorization/google`;
                    }}
                >
                    <svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true">
                        <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
                        <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
                        <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
                        <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
                    </svg>
                    Google 로그인
                </button>
            </div>
        </div>
    );
}

export default SShieldLogin;
