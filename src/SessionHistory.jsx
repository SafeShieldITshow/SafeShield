import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, setSession } from './api.js';
import './SessionHistory.css';

const formatSessionDate = (iso) => {
    if (!iso) return '';
    const date = new Date(iso);
    return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
};

const SessionHistory = ({ sessions: providedSessions, activeSessionId, onSelect, variant = 'sidebar' }) => {
    const [loadedSessions, setLoadedSessions] = useState([]);
    const navigate = useNavigate();
    const sessions = providedSessions ?? loadedSessions;

    useEffect(() => {
        if (providedSessions !== undefined) return;
        api.get('/chat/sessions')
            .then(setLoadedSessions)
            .catch(() => setLoadedSessions([]));
    }, [providedSessions]);

    const openSession = (id) => {
        if (onSelect) {
            onSelect(id);
            return;
        }
        setSession(id);
        navigate('/chat');
    };

    return (
        <section className={`ss-session-history ${variant === 'page' ? 'page-variant' : ''}`}>
            <div className="ss-session-title">상담 기록</div>
            {sessions.length === 0 ? (
                <p className="ss-session-empty">아직 기록이 없습니다.</p>
            ) : sessions.map((session) => (
                <button
                    key={session.session_id}
                    className={`ss-session-item ${session.session_id === activeSessionId ? 'active' : ''}`}
                    onClick={() => openSession(session.session_id)}
                >
                    <span>{session.preview}</span>
                    <small>{formatSessionDate(session.created_at)} · {session.message_count}개</small>
                </button>
            ))}
        </section>
    );
};

export default SessionHistory;
