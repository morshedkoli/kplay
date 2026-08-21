/** @type {import('next').NextConfig} */
const nextConfig = {
  // Emits .next/standalone with a self-contained server.js and only the
  // node_modules actually reached by the build. This is what the Docker image
  // ships — it keeps the runtime image small and removes the need to install
  // dependencies on the VPS.
  output: 'standalone',

  // Both resolve their submodules with dynamic require() at runtime, which
  // webpack can't follow. Left to bundle, googleapis in particular throws
  // "Cannot find module ./apis/drive" the first time a Drive call is made in
  // the standalone build. Marking them external makes Next trace them into
  // .next/standalone/node_modules instead.
  serverExternalPackages: ['mongodb', 'googleapis'],

  // Nothing downstream needs to know which framework serves this.
  poweredByHeader: false,
};

export default nextConfig;
