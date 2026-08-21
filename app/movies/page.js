// /movies — the movie half of the library. Series live at /series.

import LibraryGrid from '../LibraryGrid.js';

export const dynamic = 'force-dynamic';

export default function MoviesPage() {
  return <LibraryGrid kind="movie" />;
}
