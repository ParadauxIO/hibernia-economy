import 'next-auth';

declare module 'next-auth' {
  interface Session {
    keycloakSub?: string | undefined;
    minecraftUuid?: string | undefined;
    minecraftName?: string | undefined;
  }
}

declare module 'next-auth/jwt' {
  interface JWT {
    minecraftUuid?: string | undefined;
    minecraftName?: string | undefined;
  }
}
