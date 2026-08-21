// Cookie name only, in its own module so the edge middleware can import it.
// lib/admin-auth.js pulls in node:crypto, which the edge runtime can't bundle.

export const ADMIN_COOKIE = 'kdrive_admin';
