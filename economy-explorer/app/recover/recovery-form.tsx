'use client';
import { useState, useTransition } from 'react';
import { beginRecovery, checkRecovery, finishRecovery, type RecoveryResult } from '@/lib/actions/recovery';
import { loginAction } from '@/lib/actions/auth';

export function RecoveryForm() {
  const [result, setResult] = useState<RecoveryResult | null>(null);
  const [code, setCode] = useState('');
  const [pending, start] = useTransition();
  const [showPassword, setShowPassword] = useState(false);
  const [approved, setApproved] = useState(false);
  function update(next: RecoveryResult) {
    setResult(next);
    if (next.code) setCode(next.code);
    if (next.state === 'approved') setApproved(true);
    if (next.state === 'expired' || next.state === 'complete') { setCode(''); setApproved(false); }
  }
  function begin() {
    start(async () => { setApproved(false); setCode(''); update(await beginRecovery()); });
  }
  return <div>
    {result?.state === 'complete' ? <div role="status">
      <h2>Password changed</h2>
      <p>Log in with username <strong>{result.username}</strong> and your new password. Keep any leading dot in your username.</p>
      <form action={loginAction}><button className="btn btn-primary">Log in</button></form>
    </div> : <>
      {!code && !approved && <button className="btn btn-primary" disabled={pending} onClick={begin}>Start recovery</button>}
      {code && !approved && <>
        <h2>Confirm in Minecraft</h2>
        <p>Run this command within five minutes, then follow the confirmation instructions in chat:</p>
        <p className="mono" style={{ overflowWrap: 'anywhere' }}>/treasuryapi ui recover {code}</p>
        <p><strong>Only use a code from this page that you opened yourself.</strong> Never approve a code someone sends you; it would let them change your password.</p>
        <button className="btn btn-primary" disabled={pending} onClick={() => start(async () => update(await checkRecovery()))}>I confirmed in-game</button>
        {result?.state === 'pending' && <p role="status">Waiting for in-game confirmation. Keep this browser open.</p>}
      </>}
      {approved && <form onSubmit={event => {
        event.preventDefault();
        const form = event.currentTarget;
        const data = new FormData(form);
        start(async () => {
          const next = await finishRecovery(data);
          form.reset();
          update(next);
        });
      }}>
        <h2>Choose your new password</h2>
        <p>Use 12–128 characters and follow any password requirements for your account. Finish within the code’s original five-minute window.</p>
        <p><label>New password <input name="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" minLength={12} maxLength={128} required disabled={pending} /></label></p>
        <p><label>Confirm new password <input name="confirmPassword" type={showPassword ? 'text' : 'password'} autoComplete="new-password" minLength={12} maxLength={128} required disabled={pending} /></label></p>
        <p><label><input type="checkbox" checked={showPassword} onChange={event => setShowPassword(event.target.checked)} /> Show passwords</label></p>
        <button className="btn btn-primary" disabled={pending}>Change password</button>
      </form>}
      {result?.state === 'expired' && <p role="alert">This request expired, was already used, or its account link changed. Start a new recovery request.</p>}
      {result?.error && <p role="alert">{result.error}</p>}
      {(code || approved) && <p><button className="btn" disabled={pending} onClick={begin}>Start again</button></p>}
      {pending && <p role="status">Please wait…</p>}
    </>}
  </div>;
}
