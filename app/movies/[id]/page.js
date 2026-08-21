import MediaDetail from '../../MediaDetail.js';

export const dynamic = 'force-dynamic';

export default async function MovieDetailPage({ params }) {
  const { id } = await params;
  return <MediaDetail id={id} />;
}
