import { Link } from 'react-router-dom';
import { formatDuration } from '../format';
import type { Video } from '../types';
import StatusBadge from './StatusBadge';

export default function VideoCard({ video }: { video: Video }) {
  const body = (
    <>
      <h3>{video.title}</h3>
      <div className="meta">
        <StatusBadge status={video.status} />
        {video.durationSec !== null && <span>{formatDuration(video.durationSec)}</span>}
        <span>{new Date(video.createdAt).toLocaleDateString()}</span>
      </div>
    </>
  );
  return video.status === 'READY' ? (
    <Link className="card video-card ready" to={`/watch/${video.id}`}>{body}</Link>
  ) : (
    <div className="card video-card">{body}</div>
  );
}
