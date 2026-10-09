import type { Video } from './types';

const TOKEN_KEY = 'token';

export const getToken = () => localStorage.getItem(TOKEN_KEY);
export const setToken = (token: string) => localStorage.setItem(TOKEN_KEY, token);
export const clearToken = () => localStorage.removeItem(TOKEN_KEY);

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  // Auth endpoints are public: never send a (possibly stale) token there.
  const token = path.startsWith('/auth/') ? null : getToken();
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(`${import.meta.env.VITE_API_URL}/api${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });

  if (res.status === 401 && token) {
    // Expired or invalid session: drop the token and start over at the login page.
    clearToken();
    window.location.assign('/login');
  }
  if (!res.ok) {
    const error: { message: string } = await res.json();
    throw new Error(error.message);
  }
  return res.json();
}

type TokenResponse = { token: string };

export const register = (email: string, password: string) =>
  request<TokenResponse>('POST', '/auth/register', { email, password });

export const login = (email: string, password: string) =>
  request<TokenResponse>('POST', '/auth/login', { email, password });

export const createVideo = (title: string, filename: string, contentType: string) =>
  request<{ videoId: string; uploadUrl: string }>('POST', '/videos', { title, filename, contentType });

export const completeVideo = (id: string) => request<Video>('POST', `/videos/${id}/complete`);

export const listVideos = () => request<Video[]>('GET', '/videos');

export const getVideo = (id: string) => request<Video>('GET', `/videos/${id}`);

/**
 * PUT the raw file to the presigned storage URL. XHR (not fetch) for upload progress.
 * No Authorization header: the URL is already signed and extra headers break the signature.
 */
export function uploadFile(url: string, file: File, onProgress: (loaded: number, total: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    xhr.setRequestHeader('Content-Type', file.type);
    xhr.upload.onprogress = (e) => onProgress(e.loaded, e.total);
    xhr.onload = () => (xhr.status === 200 ? resolve() : reject(new Error(`Upload failed (HTTP ${xhr.status})`)));
    xhr.onerror = () => reject(new Error('Upload failed (network error)'));
    xhr.send(file);
  });
}
