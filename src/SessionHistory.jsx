import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, setSession } from './api.js';
import { formatKoreanCompactDateTime } from './time.js';
import './SessionHistory.css';

const formatSessionDate = (iso) => formatKoreanCompactDateTime(iso);

const SessionHistory = ({
    sessions: providedSessions,
    activeSessionId,
    onSelect,
    onDelete,
    variant = 'sidebar',
    loading = false,
}) => {
    const [loadedSessions, setLoadedSessions] = useState([]);
    const [isInternalLoading, setIsInternalLoading] = useState(providedSessions === undefined);
    const navigate = useNavigate();
    const sessions = providedSessions ?? loadedSessions;
    const isLoading = loading || isInternalLoading;

    useEffect(() => {
        if (providedSessions !== undefined) return;
        setIsInternalLoading(true);
        api.get('/chat/sessions')
            .then(setLoadedSessions)
            .catch(() => setLoadedSessions([]))
            .finally(() => setIsInternalLoading(false));
    }, [providedSessions]);

    const openSession = (id) => {
        if (onSelect) {
            onSelect(id);
            return;
        }
        setSession(id);
        navigate(`/chat?session=${id}`);
    };

    return (
        <section className={`ss-session-history ${variant === 'page' ? 'page-variant' : ''}`}>
            <div className="ss-session-title">상담 기록</div>
            {isLoading && sessions.length === 0 ? (
                <p className="ss-session-empty ss-session-loading">불러오는 중...</p>
            ) : sessions.length === 0 ? (
                <p className="ss-session-empty">아직 기록이 없습니다.</p>
            ) : sessions.map((session) => (
                <div
                    key={session.session_id}
                    className={`ss-session-item ${session.session_id === activeSessionId ? 'active' : ''}`}
                >
                    <button type="button" className="ss-session-open" onClick={() => openSession(session.session_id)}>
                        <span>{session.preview}</span>
                        <small>{formatSessionDate(session.created_at)} · {session.message_count}개</small>
                    </button>
                    {onDelete && (
                        <button
                            type="button"
                            className="ss-session-delete"
                            onClick={() => onDelete(session)}
                            aria-label="상담 기록 삭제"
                            title="상담 기록 삭제"
                        >
                            삭제
                        </button>
                    )}
                </div>
            ))}
        </section>
    );
};

export default SessionHistory;
