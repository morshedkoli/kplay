import './globals.css';
import { isAdmin } from '@/lib/admin-auth.js';
import Sidebar from './Sidebar.js';

export const metadata = {
  title: 'KDrive',
  description: 'A Jellyfin-style movie/TV media server backed by Google Drive with automatic TMDb matching.',
};

export default async function RootLayout({ children }) {
  const signedIn = await isAdmin();

  return (
    <html lang="en">
      <body className="min-h-screen bg-[var(--bg)] text-[var(--ink)] antialiased">
        {signedIn ? (
          <>
            <Sidebar />
            <div className="pl-16 sm:pl-56">{children}</div>
          </>
        ) : (
          children
        )}
      </body>
    </html>
  );
}
