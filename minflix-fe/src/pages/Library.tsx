import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import * as api from '../api';
import VideoCard from '../components/VideoCard';
import type { Video } from '../types';

const POLL_MS = 5000;

export default function Library() {
  const [videos, setVideos] = useState<Video[] | null>(null);
  const [error, setError] = useState('');

  const load = () => api.listVideos().then(setVideos, (err: Error) => setError(err.message));

  useEffect(() => {
    load();
  }, []);

  // Re-fetch while anything is transcoding; the interval is cleared once nothing is PROCESSING.
  const processing = videos?.some((v) => v.status === 'PROCESSING') ?? false;
  useEffect(() => {
    if (!processing) return;
    const timer = setInterval(load, POLL_MS);
    return () => clearInterval(timer);
  }, [processing]);

  if (error) return <p className="error">{error}</p>;
  if (videos === null) return <p>Loading...</p>;
  if (videos.length === 0) {
    return (
      <p className="empty">
        No videos yet. <Link to="/upload">Upload one</Link>.
      </p>
    );
  }
  return (
    <div className="grid">
      {videos.map((v) => (
        <VideoCard key={v.id} video={v} />
      ))}
    </div>
  );
}
