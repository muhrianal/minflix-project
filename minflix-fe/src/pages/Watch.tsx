import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import * as api from '../api';
import Player from '../components/Player';
import { formatDuration } from '../format';
import type { Video } from '../types';

export default function Watch() {
  const { id } = useParams();
  const [video, setVideo] = useState<Video | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    api.getVideo(id!).then(setVideo, (err: Error) => setError(err.message));
  }, [id]);

  if (error) return <p className="error">{error}</p>;
  if (video === null) return <p>Loading...</p>;
  if (video.status !== 'READY') return <p>This video is not ready yet ({video.status}).</p>;
  return (
    <>
      <Player src={video.masterPlaylistUrl!} />
      <h2 className="watch-title">{video.title}</h2>
      {video.durationSec !== null && <p className="meta">{formatDuration(video.durationSec)}</p>}
    </>
  );
}
