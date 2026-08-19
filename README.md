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

`local.properties` holds the machine-specific settings — the SDK path, the
Supabase project if you have one, and the release signing key. It is not in
version control; copy `local.properties.example` and fill it in.

**`gradle.properties` pins `org.gradle.java.home` to a JDK 21.** That is
because this machine's default `JAVA_HOME` is a Java version the Android
plugin does not accept yet. On any other machine, delete that line.

### Putting it on a phone

**Over USB.** Turn on Developer options and USB debugging on the phone, plug it
in, and:

```bash
./gradlew installDebug
```

**As a file you send yourself.** `assembleDebug` leaves an installable APK at
`app/build/outputs/apk/debug/app-debug.apk` — AirDrop it, or send it over
Telegram, Drive or email, and open it on the phone. Android will ask once for
permission to install from that app; that prompt is expected for anything not
coming from the Play Store.

A debug APK is signed with the throwaway debug key. That is fine for your own
phone, but it cannot be upgraded in place by a release build later, and Android
marks it debuggable. For something you intend to keep, sign it properly:

```bash
keytool -genkeypair -v -keystore ~/spendly-release.jks -alias spendly -keyalg RSA -keysize 2048 -validity 10000
```

Put the path and the passwords you chose into `local.properties`
(`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`), then:

```bash
./gradlew assembleRelease
```

That writes `app/build/outputs/apk/release/app-release.apk` — about a third
smaller than the debug build. **Keep the keystore file and its passwords.**
Losing them means the next version cannot be installed over this one; the only
way back is uninstalling the app, which takes its local data with it. Data in a
Supabase account survives, because it belongs to the account rather than to the
install.

Without a key configured the release build still runs and produces
`app-release-unsigned.apk`, which Android will refuse to install — signing is
what makes it installable, not an optional polish step.

### Tests

```bash
./gradlew test
```

205 unit tests over the calculation, analytics, advice, classification,
category, credential and merge rules. They need no device and no emulator.

---

## The four screens

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

### Məsləhətlər — what the figures say

Fourteen rules over the month's own numbers, each of which either fires or does
not. No model is involved and nothing is generated: the same figures always
produce the same page.

Three kinds of statement are kept apart. A **fact** is arithmetic on your
numbers. A **suggestion** is phrased as something worth looking at. A
**framework** is named and sourced where it is used. A rule the data cannot
support stays silent and says what it was missing, rather than lowering its own
standard to fill a slot.

The findings are bucketed into *Diqqət tələb edir*, *Yaxşı gedir* and *Nəzərdən
keçirməyə dəyər*, three each, ranked by the manat at stake rather than by
percentage — a 40% overrun on 5 ₼ must not outrank 200 ₼. Only the largest
finding per category is kept, so one category cannot fill a bucket.

Three of the panels need to know what your spending is *for*, so each expense
category can carry a kind — **zəruri**, **istəyə bağlı**, **borc ödənişi** or
**yığım** — set in **Büdcə → Kateqoriyalar**. Nothing guesses it, and nothing
is assumed: *Ehtiyac və istək*, the *50/30/20* comparison and the *təcili
ehtiyat fondu* target all report how much of the month's spending they could
account for, and stay silent below 90% rather than publishing a confident
number that is wrong. When they do stay silent they name the categories still
unclassified, so the fix is one tap away.

**Metodologiya** at the bottom lists every reference, its origin, and the date
it was last verified. Guidance goes out of date and no offline app can know
that; what it can do is refuse to present an unchecked reference as current.
References older than a year are flagged on screen.

This is not financial advice, and the app says so where it matters.

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
| Add, rename or classify a category | **Büdcə** → **Kateqoriyalar** |
| Wipe one month's plan | **Büdcə** → **Silmə** → **Planı sil** → **Təsdiqlə** |
| Wipe everything | **Büdcə** → **Silmə** → **Bütün məlumatları sil** |
| See the account, or sign out | The round button at the top right |
| Change your password | The account button → **Şifrə** → **Dəyiş** |
| Reset a forgotten password | **Şifrənizi unutmusunuz?** on the sign-in screen |

Every destructive action needs a second tap to confirm.

**Renaming a category is safe.** Rename `Ərzaq` to `Yemək` and every
transaction, budget line and planned figure that used it comes along. No
amount changes, and no total moves.

**Deleting will not quietly take your history with it.** If something uses the
category, the app says how much depends on it and asks where that history
should move to first.

---

## Where your data lives

**Always on the device.** One JSON file in the app's own storage. Every change
is written there first, before anything is asked of the network — so an edit
made with no signal is saved, not held in memory and lost when the app closes.

With no Supabase project configured that file is the whole story: no account,
no server, nothing leaves the phone, and no sign-in screen ever appears.

**Optionally, in your own Supabase project**, which gets you a real database, a
backup, and the same data on your phone and in the web app. Put the project
URL and publishable key in `local.properties`:

```
SUPABASE_URL=https://YOUR-PROJECT.supabase.co
SUPABASE_PUBLISHABLE_KEY=YOUR-PUBLISHABLE-KEY
```

Then, in the Supabase dashboard:

1. **Create the tables** — paste [`supabase/schema.sql`](supabase/schema.sql)
   into the SQL editor and run it. Running it again later is safe, and is how
   you pick up changes — the `categories.kind` column is the most recent.
2. **Turn on email sign-in** — Authentication → Sign In / Providers → Email,
   with sign-ups allowed.

With a project configured, the app opens on a sign-in screen. Your data belongs
to that account, so the same figures are there from any device that signs in.
It is the same account the web app uses.

**Being offline is a normal state, not a failure.** The device's file stays the
working copy; the account is where it is shared from. When the server cannot be
reached, the app says *"dəyişikliklər cihazda saxlanılıb, sinxronizasiya
gözləyir"* and keeps working. The queue goes out on its own the moment a
network appears, when you return to the app, or when you tap **İndi göndər**.

Bringing the two together follows one rule: **rows this device changed while it
could not reach the server win; every other row comes from the server.** So
work done on the phone is never silently replaced by what the browser had, and
anything entered elsewhere in the meantime still arrives. Two devices editing
the very same transaction while both offline is the case it cannot resolve —
the one that syncs second wins, and nothing is lost that was not deliberately
replaced.

A rejection from the server is a different thing from being offline, and is
said differently: the red banner means the server answered and refused, and it
names the setup step that fixes it. Your change is still on the device either
way.

The profile — the round button at the top right — shows who is signed in, when
the account was created, what it holds, and the way out. It also shows the user
id, and lets you copy it: every row is scoped to that id, so anyone recovering
records that belong to an older identity needs to be able to read their own.

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
  domain/insights/  The advice engine, its thresholds and its references.
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
changed; `SyncingRepository` puts the two together — device first, server
after, with a second snapshot recording what the server last acknowledged so
this device's unsent work can be told apart from rows it simply has not seen.
Nothing above that interface knows which one it got.
