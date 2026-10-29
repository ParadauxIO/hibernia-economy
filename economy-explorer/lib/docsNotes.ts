import { z } from 'zod';

const noteSchema = z.object({
  title: z.string().trim().min(1).max(200),
  text: z.string().trim().min(1).max(5000),
  link: z.object({
    label: z.string().trim().min(1).max(200),
    href: z.string().url().refine((value) => ['https:', 'http:'].includes(new URL(value).protocol)),
  }).strict().optional(),
}).strict();

// Exact public docs paths; admin notes must not be published through this API.
const notesSchema = z.record(
  z.string().regex(/^\/docs(?:\/[a-z0-9]+(?:-[a-z0-9]+)*)*$/)
    .refine((value) => value !== '/docs/admin' && !value.startsWith('/docs/admin/')),
  noteSchema,
);

export type DocNote = z.infer<typeof noteSchema>;
export type DocNotes = Record<string, DocNote>;

export function parseDocNotes(raw: string | undefined): DocNotes {
  if (!raw?.trim()) return {};
  return notesSchema.parse(JSON.parse(raw));
}
