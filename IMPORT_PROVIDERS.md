# Import provider settings

Open the navigation menu → **Settings → Import providers**.

Use **+ Add provider** for another service, or tap an existing provider to change its domains and endpoints. No code changes or app rebuild are needed for compatible API domain/path changes.

## Fields

| Field | What to enter | Current Shinigami example |
| --- | --- | --- |
| Provider name | A unique label you recognize | `Shinigami` |
| Website hosts | Domains accepted when you paste a chapter link; one per line | `shinigami.asia` and `*.shinigami.asia` |
| API base URL | HTTPS API address, including any version prefix | `https://api.shngm.io/v1/` |
| Chapter link path | Website path; `{id}` is extracted from the pasted URL | `/chapter/{id}` |
| Chapter endpoint path | Relative path used for the first GET | `chapter/detail/{id}` |
| Manga endpoint path | Relative path used for the second GET; its ID comes from the chapter response | `manga/detail/{id}` |
| Cover image hosts | Domains allowed for downloaded covers; one per line | `assets.shngm.id` and `assets.shngm.io` |
| Provider enabled | Whether this provider can recognize chapter links | Checked |
| Require UUID chapter and manga IDs | Keep checked for UUIDs; uncheck for numeric or URL-safe IDs | Checked |

Website/cover hosts are domain names, not full URLs. `*.example.com` accepts subdomains such as `reader.example.com`, but does **not** include `example.com` itself; add that separately if needed. Multiple website domains can share one API configuration.

Endpoint paths must be relative to the API base: no leading slash, query string, credentials, or `..`. Every link/endpoint template must contain `{id}` exactly once. For example, base `https://api.example.com/v2/` plus `chapters/{id}/metadata` produces `https://api.example.com/v2/chapters/CHAPTER_ID/metadata`.

## Check and use a provider

1. Enter its website hosts and API configuration.
2. Paste a sample into **Test chapter URL (optional)**.
3. Tap **Preview requests** to inspect the extracted ID and request addresses. This preview does not contact the server or validate its response.
4. Tap **Save provider**. Changes survive app reopening.
5. Open **New Media**, paste the chapter link into **Title**, and tap the link icon. Review the imported draft before saving the media.

If a domain changes, edit Website hosts, API base URL, or Cover image hosts as needed. If API paths change, edit the endpoint templates. Old hosts can remain as extra lines while they are still useful.

Use a provider's **⋮** menu to edit, enable/disable, or delete it. Disabling/deleting a provider never deletes saved media or covers. **Restore default provider** replaces all provider configurations with the shipped Shinigami configuration, after confirmation. Deleting all providers leaves link import disabled; defaults are not silently recreated.

## Compatibility and privacy

This configures the existing two-GET importer, not a universal website scraper. The API must use the current response format:

- Both responses: `retcode: 0` and a `data` object.
- Chapter: `chapter_id`, `manga_id`, and nonnegative `chapter_number`.
- Manga: matching `manga_id` and `title`; optional description, cover URLs, alternative title, release year, community rating, and the existing `taxonomy` arrays.

Different JSON field names, authentication, request headers, POST endpoints, and arbitrary HTML scraping are not configurable in this version. Numeric release-status codes still require review. Personal progress comes from the opened chapter, not the latest chapter.

Only add services you trust. Requests use HTTPS, refuse redirects, and have timeouts and a bounded response size. Cover URLs must match that provider's allowed cover hosts; leave the field empty to skip covers. Overlapping enabled host rules with the same link path are rejected, and ambiguous matches never silently choose a service.

Settings are kept in app-private preferences. They are **not included in collection JSON exports**; changing or restoring a collection backup does not change your endpoint settings. Android's separate device/app backup behavior follows the app's existing backup rules.
