// /series — the series half of the library. Movies live at /movies.

import LibraryGrid from '../LibraryGrid.js';

export const dynamic = 'force-dynamic';

export default function SeriesPage() {
  return <LibraryGrid kind="series" />;
}
