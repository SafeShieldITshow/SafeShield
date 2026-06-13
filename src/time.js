const KOREA_TIME_ZONE = 'Asia/Seoul';

const hasExplicitTimeZone = (value) => /(?:z|[+-]\d{2}:?\d{2})$/i.test(value);

const parseTimestamp = (value = new Date()) => {
    if (value instanceof Date) return value;
    if (!value) return null;

    const text = String(value).trim();
    if (!text) return null;

    const normalized = /^\d{4}-\d{2}-\d{2}T/.test(text) && !hasExplicitTimeZone(text)
        ? `${text}Z`
        : text;
    const date = new Date(normalized);
    return Number.isNaN(date.getTime()) ? null : date;
};

const partsFor = (value, options) => {
    const date = parseTimestamp(value);
    if (!date) return null;
    return new Intl.DateTimeFormat('ko-KR', {
        timeZone: KOREA_TIME_ZONE,
        ...options,
    }).formatToParts(date).reduce((items, part) => {
        items[part.type] = part.value;
        return items;
    }, {});
};

export const formatKoreanTime = (value = new Date()) => {
    const date = parseTimestamp(value);
    if (!date) return '';
    return new Intl.DateTimeFormat('ko-KR', {
        timeZone: KOREA_TIME_ZONE,
        hour: '2-digit',
        minute: '2-digit',
    }).format(date);
};

export const formatKoreanDateTime = (value) => {
    const date = parseTimestamp(value);
    if (!date) return '';
    return new Intl.DateTimeFormat('ko-KR', {
        timeZone: KOREA_TIME_ZONE,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
    }).format(date);
};

export const formatKoreanCompactDateTime = (value) => {
    const parts = partsFor(value, {
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
    });
    if (!parts) return '';
    return `${parts.month}/${parts.day} ${parts.hour}:${parts.minute}`;
};

export const formatKoreanNumericDateTime = (value) => {
    const parts = partsFor(value, {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
    });
    if (!parts) return '';
    return `${parts.year}.${parts.month}.${parts.day} ${parts.hour}:${parts.minute}`;
};
