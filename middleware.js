// Page-level gate for the web app.
//
// This only checks that a session cookie is PRESENT — middleware runs on the
// edge runtime where node:crypto isn't available, so it can't verify the HMAC.
// Real verification happens in the pages and API routes via isAdmin() /
// requireDeviceOrSession(). A forged cookie gets past this redirect and then
// bounces off the actual check; it never reaches data.

import { NextResponse } from 'next/server';

import { ADMIN_COOKIE } from '@/lib/session-cookie.js';

const PUBLIC_PATHS = ['/login'];

export function middleware(request) {
  const { pathname } = request.nextUrl;

  if (PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(`${p}/`))) {
    return NextResponse.next();
  }

  if (request.cookies.get(ADMIN_COOKIE)?.value) {
    return NextResponse.next();
  }

  const url = request.nextUrl.clone();
  url.pathname = '/login';
  // Come back to where they were aiming once signed in.
  url.searchParams.set('next', pathname);
  return NextResponse.redirect(url);
}

export const config = {
  // Pages only. API routes do their own auth and must be able to answer 401
  // to an API client rather than redirect it to an HTML login page.
  //
  // '/' is deliberately absent: the home page is the site's front door and
  // renders for a signed-out visitor too. It decides for itself what to show
  // to whom (app/page.js) rather than being bounced to the password box.
  matcher: ['/movies', '/movies/:path*', '/series', '/series/:path*', '/admin/:path*'],
};
