import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import './SShieldLogin.css';

function SShieldLogin() {
    const [activeTab, setActiveTab] = useState('login');
    const navigate = useNavigate();

    return (
        <div className="login-container">
            <div className="top-logo clickable" onClick={() => navigate('/')}>
                S-<span className="logo-accent">Shield</span>
            </div>

            <div className="form-wrapper animated-entrance">
                <div className="tab-group-container">
                    <div className={`tab-active-bar ${activeTab === 'login' ? 'pos-right' : 'pos-left'}`}></div>
                    <button
                        className={`tab-btn-item ${activeTab === 'signup' ? 'selected' : ''}`}
                        onClick={() => setActiveTab('signup')}
                    >
                        회원가입
                    </button>
                    <button
                        className={`tab-btn-item ${activeTab === 'login' ? 'selected' : ''}`}
                        onClick={() => setActiveTab('login')}
                    >
                        로그인
                    </button>
                </div>

                <div className="input-group-container">
                    <label className="input-label">아이디</label>
                    <input type="text" placeholder="아이디를 입력하세요." className="clean-purple-input" />
                </div>

                <div className="input-group-container last-input">
                    <label className="input-label">비밀번호</label>
                    <input type="password" placeholder="비밀번호를 입력하세요." className="clean-purple-input" />
                </div>

                <button className="google-auth-btn-minimal">
                    <img
                        src="https://upload.wikimedia.org/wikipedia/commons/5/53/Google_%22G%22_Logo.svg"
                        alt="Google"
                        className="google-svg-icon"
                    />
                    {activeTab === 'login' ? 'Sign in with Google' : 'Sign up with Google'}
                </button>

                {/* 🔥 클릭하면 챗봇 페이지(/chat)로 이동! */}
                <button
                    className="main-action-btn"
                    onClick={() => activeTab === 'login' ? navigate('/chat') : setActiveTab('login')}
                >
                    {activeTab === 'login' ? '로그인' : '회원가입'}
                </button>
            </div>
        </div>
    );
}

export default SShieldLogin;