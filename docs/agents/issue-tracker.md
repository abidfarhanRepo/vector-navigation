# Issue tracker: Local Markdown (Vector)

Issues, specs (PRDs), and tickets for the Vector workspace live as local markdown
files under `.scratch/`. This is the recommended fallback for remote-less repos
(`gh` is not installed and the polyrepo has no GitHub remotes).

Vector is a polyrepo (~33 `vector-*` git repos under this workspace root). An
issue here typically spans one or more repos; the directory lives at the
workspace root and cross-references the affected repos by name.

## Conventions

- One effort / feature per directory: `.scratch/<effort-slug>/`
- The spec is `.scratch/<effort-slug>/spec.md`
- Implementation tickets are one file per ticket at
  `.scratch/<effort-slug>/issues/<NN>-<slug>.md`, numbered from `01` — never a
  single combined tickets file.
- Triage state is recorded as a `Status:` line near the top of each issue file
  (see `triage-labels.md` for the role strings).
- Comments / conversation history append to the bottom under a `## Comments`
  heading.
- Cross-repo scope: list affected repos under an `Affects:` line, e.g.
  `Affects: vector-routing, vector-web`.

## When a skill says "publish to the issue tracker"

Create a new file under `.scratch/<effort-slug>/` (creating the directory if
needed). There is no remote to push to — these files are committed to the
relevant `vector-*` repo as part of normal work, or kept at the workspace root.

## When a skill says "fetch the relevant ticket"

Read the file at the referenced path. The user normally passes the path or
issue number directly.

## Wayfinding operations (used by `/wayfinder`)

- **Map**: `.scratch/<effort>/map.md` — Notes / Decisions-so-far / Fog body.
- **Child ticket**: `.scratch/<effort>/issues/NN-<slug>.md`, numbered from `01`,
  with the question in the body. A `Type:` line records the ticket type
  (`research`/`prototype`/`grilling`/`task`); a `Status:` line records
  `claimed`/`resolved`.
- **Blocking**: a `Blocked by: NN, NN` line near the top. A ticket is unblocked
  when every file it lists is `resolved`.
- **Frontier**: scan `.scratch/<effort>/issues/` for files that are open,
  unblocked, and unclaimed; first by number wins.
- **Claim**: set `Status: claimed` and save before any work.
- **Resolve**: append the answer under an `## Answer` heading, set
  `Status: resolved`, then append a context pointer (gist + link) to `map.md`.

## Note on Vector governance

For decisions that are architectural and binding across the whole platform,
prefer a formal ADR in `vector-governance/adr/` (immutable, registry-as-truth)
over an issue-file conversation. The local-tracker files above are for
in-flight work planning, not for supplanting the governance ADRs.
