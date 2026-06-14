import { Navigate, Route, Routes, useNavigate, useSearchParams } from 'react-router-dom';
import { useEffect, useState } from 'react';
import './App.css';
import SShieldLogin from './SShieldLogin';
import SShieldChat from './SShieldChat.jsx';
import SShieldResult from './SShieldResult.jsx';
import SShieldMypage from './SShieldMypage.jsx';
import { api, clearSession, hasToken, setToken } from './api.js';

function LoginRequiredRedirect() {
    const navigate = useNavigate();

    useEffect(() => {
        alert('로그인이 필요합니다.');
        navigate('/', { replace: true });
    }, [navigate]);

    return <div style={{ color: '#fff', textAlign: 'center', marginTop: 80 }}>메인으로 이동 중...</div>;
}

function ProtectedRoute({ children }) {
    const [status, setStatus] = useState(() => hasToken() ? 'checking' : 'guest');

    useEffect(() => {
        let alive = true;

        if (!hasToken()) {
            clearSession();
            setStatus('guest');
            return;
        }

        api.get('/auth/me')
            .then(() => {
                if (alive) setStatus('authed');
            })
            .catch(() => {
                clearSession();
                if (alive) setStatus('guest');
            });

        return () => {
            alive = false;
        };
    }, []);

    if (status === 'checking') {
        return <div style={{ color: '#fff', textAlign: 'center', marginTop: 80 }}>로그인 상태 확인 중...</div>;
    }

    return status === 'authed' ? children : <LoginRequiredRedirect />;
}

function OAuth2Callback() {
    const [params] = useSearchParams();
    const navigate = useNavigate();

    useEffect(() => {
        const token = params.get('token');
        const username = params.get('username');
        const name = params.get('name');

        if (token) {
            setToken(token);
            localStorage.setItem('ss_username', username || '');
            localStorage.setItem('ss_name', name || username || '');
            navigate('/chat', { replace: true });
        } else {
            navigate('/login', { replace: true });
        }
    }, [navigate, params]);

    return <div style={{ color: '#fff', textAlign: 'center', marginTop: 80 }}>로그인 처리 중...</div>;
}

function MainHome() {
    const navigate = useNavigate();

    return (
        <div className="root-container">
            <div className="gradient-overlay"></div>
            <div className="logo-text">S-<span className="logo-accent">Shield</span></div>

            <div className="main-content">
                <h1 className="title-h1">
                    <div className="line-wrapper">
                        <span className="typing-txt anim-1 text-white">당신의 상황,</span>
                    </div>
                    <div className="line-wrapper line-flex">
                        <span className="fade-txt anim-2 text-purple">법 기준</span>
                        <span className="typing-txt anim-3 text-white">으로</span>
                    </div>
                    <div className="line-wrapper line-flex">
                        <span className="fade-txt anim-4 text-purple">분석</span>
                        <span className="typing-txt anim-5 text-white">해드립니다</span>
                    </div>
                </h1>

                <p className="sub-desc anim-6">
                    학교폭력 상황을 입력하면 관련 법령과<br className="pc-br" />
                    증거 준비 방법, 신고 절차를 안내합니다.
                </p>

                <div className="anim-7">
                    <button className="start-btn" onClick={() => navigate('/chat?new=1')}>
                        시작하기
                    </button>
                    <p className="info-note">* 상담 기록과 리포트 저장을 위해 로그인이 필요합니다.</p>
                </div>
            </div>

            <div className="chat-wrapper" aria-hidden="true">
                <div className="bubble b-white-1"></div>
                <div className="bubble b-purple-1"></div>
                <div className="bubble b-white-2"></div>
                <div className="bubble b-purple-2"></div>
            </div>
        </div>
    );
}

function App() {
    return (
        <Routes>
            <Route path="/" element={<MainHome />} />
            <Route path="/login" element={<SShieldLogin />} />
            <Route path="/oauth2/callback" element={<OAuth2Callback />} />
            <Route path="/chat" element={<SShieldChat />} />
            <Route path="/result" element={<SShieldResult />} />
            <Route path="/mypage" element={<ProtectedRoute><SShieldMypage /></ProtectedRoute>} />
            <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
    );
}

export default App;
