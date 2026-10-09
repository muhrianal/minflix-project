import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth';

export default function Navbar() {
  const { logout } = useAuth();
  return (
    <nav className="navbar">
      <span className="brand">minflix</span>
      <NavLink to="/" end>Library</NavLink>
      <NavLink to="/upload">Upload</NavLink>
      <button className="link" onClick={logout}>Logout</button>
    </nav>
  );
}
