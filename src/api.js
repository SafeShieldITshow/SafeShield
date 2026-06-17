export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const getToken = () => {
    const token = localStorage.getItem('ss_token');
    if (!token) return null;
    if (token.length > 4096 || token.split('.').length !== 3) {
        clearSession();
        return null;
    }
    return token;
};

const headers = (path) => ({
    'Content-Type': 'application/json',
    ...(getToken() && path !== '/auth/login' && path !== '/auth/signup' ? { Authorization: `Bearer ${getToken()}` } : {}),
});

const apiError = (message, status = 0, data = {}) => {
    const error = new Error(message);
    error.status = status;
    error.data = data;
    return error;
};

async function req(method, path, body) {
    let res;
    try {
        res = await fetch(`${API_BASE_URL}${path}`, {
            method,
            headers: headers(path),
            ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
        });
    } catch {
        throw apiError('서버에 연결할 수 없습니다. 새로고침 후 다시 시도해 주세요.');
    }

    const data = await res.json().catch(() => ({}));

    if (res.status === 401) {
        clearSession();
        throw apiError('로그인이 필요합니다. 다시 로그인해 주세요.', res.status, data);
    }

    if (!res.ok) {
        throw apiError(data.detail || data.message || data.error || '요청 처리 중 오류가 발생했습니다.', res.status, data);
    }

    return data;
}

export const api = {
    post: (path, body) => req('POST', path, body),
    get: (path) => req('GET', path),
    del: (path) => req('DELETE', path),
};

export const setToken = (token) => localStorage.setItem('ss_token', token);
export const removeToken = () => localStorage.removeItem('ss_token');
export const hasToken = () => !!getToken();
export const setSession = (id) => localStorage.setItem('ss_session', String(id));
export const getSession = () => {
    const value = localStorage.getItem('ss_session');
    return value ? parseInt(value, 10) : null;
};
export const clearSession = () => {
    localStorage.removeItem('ss_token');
    localStorage.removeItem('ss_session');
    localStorage.removeItem('ss_username');
    localStorage.removeItem('ss_name');
};
