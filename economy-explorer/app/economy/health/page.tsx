import { redirect } from 'next/navigation';
export default async function HealthPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  redirect(sp.days === '7' ? '/?days=7' : '/?days=30');
}
