import { RecoveryForm } from './recovery-form';

export const dynamic = 'force-dynamic';
export const metadata = { title: 'Recover your account', robots: { index: false, follow: false } };

export default async function RecoveryPage() {
  return <>
    <div className="page-heading"><h1>Recover your account</h1></div>
    <div className="card">
      <p>Confirm your identity in Minecraft, then choose a new web password here. You need an account already linked to your Minecraft player.</p>
      <p>This works on Java and Bedrock. You will type a short code in Minecraft; you choose your password on this page.</p>
      <RecoveryForm />
    </div>
  </>;
}
