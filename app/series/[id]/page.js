import MediaDetail from '../../MediaDetail.js';

export const dynamic = 'force-dynamic';

export default async function SeriesDetailPage({ params }) {
  const { id } = await params;
  return <MediaDetail id={id} />;
}
