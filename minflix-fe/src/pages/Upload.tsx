import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import * as api from '../api';

const MB = 1024 * 1024;

export default function Upload() {
  const navigate = useNavigate();
  const [title, setTitle] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [progress, setProgress] = useState<{ loaded: number; total: number } | null>(null);
  const [error, setError] = useState('');
  const uploading = progress !== null;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const f = file!;
    setError('');
    setProgress({ loaded: 0, total: f.size });
    try {
      const { videoId, uploadUrl } = await api.createVideo(title, f.name, f.type);
      await api.uploadFile(uploadUrl, f, (loaded, total) => setProgress({ loaded, total }));
      await api.completeVideo(videoId);
      navigate('/');
    } catch (err) {
      setError((err as Error).message);
      setProgress(null);
    }
  };

  return (
    <form className="card upload-form" onSubmit={submit}>
      <h1>Upload</h1>
      <input placeholder="Title" value={title} onChange={(e) => setTitle(e.target.value)} disabled={uploading} />
      <input type="file" accept="video/*" onChange={(e) => setFile(e.target.files?.[0] ?? null)} disabled={uploading} />
      {progress && (
        <>
          <progress value={progress.loaded} max={progress.total} />
          <span>
            {Math.round((progress.loaded / progress.total) * 100)}% ({(progress.loaded / MB).toFixed(1)} /{' '}
            {(progress.total / MB).toFixed(1)} MB)
          </span>
        </>
      )}
      {error && <p className="error">{error}</p>}
      <button type="submit" disabled={!title.trim() || !file || uploading}>
        Upload
      </button>
    </form>
  );
}
