# Tenant documentation notes

Operators can add a note above a public docs page through the Explorer's runtime
`DOCS_TOP_NOTES` environment variable. Configure it in the tenant deployment's
environment or ConfigMap and restart its pods. The shared image and Markdown do
not need to change. Unset or `{}` means no notes.

The value is a JSON object keyed by exact docs URLs, such as `/docs` or
`/docs/guides/api`, without a trailing slash, query string or fragment. Notes
apply only to that page, not its descendants. Each note contains a required
`title` (1–200 characters), `text` (1–5000 characters), and an optional `link`
with `label` (1–200 characters) and an absolute HTTP or HTTPS `href`.
Text is plain text; line breaks are preserved. HTML and Markdown are not rendered.

For PAR-550, set this value **only on the DemocracyCraft deployment**:

```json
{
  "/docs/guides/api": {
    "title": "Treasury licence required",
    "text": "On DemocracyCraft, obtain a Treasury licence at the university before issuing your Treasury API key.",
    "link": {
      "label": "Treasury Licence guide",
      "href": "https://wiki.democracycraft.net/Treasury_Licence"
    }
  }
}
```

The [Treasury Licence wiki](https://wiki.democracycraft.net/Treasury_Licence)
describes the licence prerequisite; the university instruction is supplied by
[PAR-550](https://app.taskl.ie/w/paradaux/issues/PAR-550).
This example is opt-in configuration, not an application default.

All configured notes are public through `/api/docs/notes`. Do not include secrets
or private instructions. Admin docs paths are rejected. The endpoint validates
the whole configuration and returns 503 for invalid JSON, unsupported fields,
invalid paths or unsafe links; it logs a generic configuration error. The guide
remains readable if notes cannot load. Unknown but well-formed page paths have no
effect until a matching page exists.

Notes load in the browser after hydration, like live tax rates. They require
JavaScript and are not included in the static document metadata or table of
contents. Responses are not cached, and the endpoint reads only this deployment's
environment; there is no tenant selector or editing UI.
