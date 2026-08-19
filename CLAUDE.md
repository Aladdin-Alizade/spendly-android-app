# Spendly for Android

The Android app. There is a second implementation of the same product:

    ../spendly    (React / TypeScript, Vite)

## The rule: the two apps stay in step

The two are one product with one data model, one Supabase schema, one set of
domain rules and one set of Azerbaijani strings. They are not allowed to
drift. **Whenever you change something here that the web app also has, port
the change there in the same session, before reporting the work done** — and
the same in reverse, a web change comes back here.

A change that lands on one side only means the two apps compute different
numbers from the same account, and the next person to touch either one has no
way to tell which is right.

The full file map and the list of what is worth porting live in
`../spendly/CLAUDE.md`. Read it before starting a port.

In short: port the product — data model, domain logic, `supabase/schema.sql`
(the two copies must stay byte-identical), sync and merge behaviour,
Azerbaijani strings, screen structure, tests. Do not port the platform —
Compose vs JSX, `ui/theme/Theme.kt` vs `styles.css`, `SnapshotStore` vs
localStorage. Match the behaviour, then write it the way the target codebase
already writes things.

### Before saying the work is done

- both sides changed, or a sentence saying why the web side needs nothing
- android: `./gradlew test`
- web: `npm test` in `../spendly`
- if `supabase/schema.sql` moved, `diff` the two copies and expect no output
