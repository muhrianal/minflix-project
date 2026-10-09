import type { VideoStatus } from '../types';

export default function StatusBadge({ status }: { status: VideoStatus }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{status}</span>;
}
