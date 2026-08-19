# Spendly for Android

The Android build of [Spendly](../spendly) — a personal budget app for one
person and their own money. You record what you earned and spent, plan what
each month is supposed to cost, and the app works out every total from those
two things.

Amounts are in manat (`1,250.00 ₼`) and the interface is in Azerbaijani.

- **Nothing is typed twice.** Totals, differences and percentages are all
  calculated. There is no field to keep up to date by hand.
- **Nothing is invented.** Every figure on screen traces back to a transaction
  you entered. Tap any number to see exactly which ones it came from.
- **Nothing is a judgement.** The app will tell you food spending is 38% lower
  than last month. It will never tell you that you spend too much on food.

This is a native rewrite, not a wrapped web page: Kotlin and Jetpack Compose,
with the calculation rules ported from the web app function for function and
its test suite ported case for case. Both builds compute the same figures, or
one of them is wrong.

---

## Building it

Requires the Android SDK and a JDK — no separate Gradle install; the wrapper
handles that.

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

Install it on a connected device or a running emulator:

```bash
./gradlew installDebug
```

`local.properties` holds the machine-specific settings — the SDK path, and the
Supabase project if you have one. It is not in version control; copy
`local.properties.example` and fill it in.

**`gradle.properties` pins `org.gradle.java.home` to a JDK 21.** That is
because this machine's default `JAVA_HOME` is a Java version the Android
plugin does not accept yet. On any other machine, delete that line.

### Tests

```bash
./gradlew test
```

129 unit tests over the calculation, analytics, category and validation rules —
the web app's suite, carried across. They need no device and no emulator.

---

## The three screens

The month you are looking at is set in the header, and applies everywhere.

### İcmal — the overview

Your dashboard. It answers, in order: where do I stand, how did money move,
where did it go, was that the plan, and when did it happen.

| Panel | Tells you |
| --- | --- |
| **Balans** | What you have, and how that moved since last month |
| **Büdcə** | How much of the month's plan is used, and by which categories |
| **Pul dövriyyəsi** | What came in, what went out, what you kept |
| **Pul axını** | Income against spending, week by week, with your balance over the top |
| **Nə dəyişdi** | Plain sentences about what moved since last period |
| **Pul hara getdi** | Every category, ranked, with its share and its change |
| **Plan və faktiki** | Each category against what you planned for it |
| **Gəlir mənbələri** | Where income came from, against what you planned |
| **Xərc tempi** | Your spend per day, and where that rate lands by month end |
| **Günlük hərəkət** | Every day of the month, income up and spending down |
| **Həftənin günləri** | Which days of the week carry your spending |
| **Ən çox təkrarlanan** | What you keep buying |
| **Müqayisə** | This period against the one before it, line by line |

**Panels you do not see are panels with no data behind them.** A month with
nothing in it shows a short page instead of a wall of zeroes.

Almost everything is tappable. Tap a category, a day or a bar and you get the
transactions behind it.

### Əməliyyatlar — transactions

Everything recorded for the month, newest first. Filter to income or expenses
with the tabs. Tap any row to edit or delete it.

### Büdcə — the plan

What the month is *supposed* to look like: planned income per category,
planned spending lines grouped by category next to what you actually spent,
the planned remainder, your own category lists, and the delete tools at the
very bottom.

---

## How to do things

| I want to… | Do this |
| --- | --- |
| Record something | The **+** button |
| Change or remove one entry | Tap it → edit, or **Sil** → **Silinməni təsdiqlə** |
| Look at another month | The month arrows in the header, or tap the month name |
| Look at a longer stretch | The period buttons on İcmal: **Ay · Keçən · 3 ay · 6 ay · İl** |
| Start planning a month | **Büdcə** → **Planı köçür** to copy last month's plan forward |
| Add a planned expense | **Büdcə** → **Sətir əlavə et** |
| Set expected income | **Büdcə** → **Planlaşdırılan gəlir** → **Dəyiş** |
| Add or rename a category | **Büdcə** → **Kateqoriyalar** |
| Wipe one month's plan | **Büdcə** → **Silmə** → **Planı sil** → **Təsdiqlə** |
| Wipe everything | **Büdcə** → **Silmə** → **Bütün məlumatları sil** |

Every destructive action needs a second tap to confirm.

**Renaming a category is safe.** Rename `Ərzaq` to `Yemək` and every
transaction, budget line and planned figure that used it comes along. No
amount changes, and no total moves.

**Deleting will not quietly take your history with it.** If something uses the
category, the app says how much depends on it and asks where that history
should move to first.

---

## Where your data lives

**By default, on the device.** One JSON file in the app's own storage. No
account, no server, nothing leaves the phone — and nothing to sign into, so no
sign-in screen ever appears.

**Optionally, in your own Supabase project**, which gets you a real database, a
backup, and the same data on your phone and in the web app. Put the project
URL and publishable key in `local.properties`:

```
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR-PUBLISHABLE-KEY
```

Then, in the Supabase dashboard:

1. **Create the tables** — paste [`supabase/schema.sql`](supabase/schema.sql)
   into the SQL editor and run it. Running it again later is safe.
2. **Turn on email sign-in** — Authentication → Sign In / Providers → Email,
   with sign-ups allowed.

With a project configured, the app opens on a sign-in screen. Your data belongs
to that account, so the same figures are there from any device that signs in.
It is the same account the web app uses.

The publishable key is meant to ship inside an app; it is not what protects the
data. Row level security is: every table is scoped to `auth.uid()`, so that key
on its own reads nothing. Never put the `service_role` key in this file.

If a write cannot reach the backend, a banner says so rather than letting the
edit look saved — and where the failure is a setup step (tables not created,
sign-ups switched off, session expired) the banner names the step.

---

## How it is put together

```
app/src/main/java/az/spendly/
  domain/      Every rule and every figure. Pure Kotlin, no Android imports.
  data/        The persistence boundary: one interface, two implementations.
  store/       FinanceViewModel and AuthViewModel — the app's state.
  ui/          Compose: the theme, the three screens, the charts, the dialogs.
app/src/test/  The suite, over domain/ and data/.
```

`domain/` is where the money is decided, and it knows nothing about Android or
about where data is stored — which is why it can be tested on the JVM in a
couple of seconds. Every function in it names the spreadsheet cell it
reproduces, because this app replaces a specific monthly Google Sheet and
those figures had to keep matching.

`data/FinanceRepository` is the only way anything is stored or read.
`LocalRepository` writes one JSON snapshot to app storage; `SupabaseRepository`
diffs the snapshot against what it last wrote and sends only the rows that
changed. Nothing above that interface knows which one it got.
