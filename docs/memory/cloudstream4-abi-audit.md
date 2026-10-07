---
name: cloudstream4-abi-audit
description: What can actually leave :app without breaking the extension ABI — the 2026-09-17 movability audit, the $stable trap, and the decisions it raised
metadata:
  type: project
---

Read-only audit (workflow `wf_b96c8a1f-309`, output in task `wp3nun9s0`) of 33 :app files, every verdict
checked with javap against `classes.jar`. **The plan's Phases 2–3 are mostly infeasible without ABI
breaks** — "no android import" was never movability.

**Movable today with zero delta:** `DohProvidersKt` (→ :core:network jvmCommonMain, recommended first
PR), the const facades `DataStoreKt` / `DataStoreHelperKt` (→ :core:data). With small, reviewable
deltas: AbstractSubtitleEntities, SyncSearchViewModel, IGeneratorKt vals, Auth* DTOs (→ :core:model),
DdosGuardKiller, SSLTrustManager, APIRepository (needs afterPluginsLoadedEvent decoupled).

**Stays in :app (Android in ABI or :app-only deps):** UiText/TextUtilKt, DataStore, DataStoreHelper
(+ all nested models — extracting changes FQNs), BackupUtils, RequestsHelperKt, CloudflareKiller,
AccountManager/AuthAPI/AuthRepo/SyncRepo and all providers, VideoGenerator hierarchy (ExtractorUri has
android.net.Uri), UriSerializer, videoskip, Torrent.

**The `$stable` trap — verified by me.** :app is Compose-compiled, so every class carries
`public static final int $stable` + `@StabilityInferred`. Moving to a non-Compose module drops them
(PR #4 did, for Event/EmptyEvent/Vector2/IDisposableHelper; PR #4 body corrected). Applying
`cloudstream.kmp.compose` to the receiving module restores them byte-identically (static initializers
match, Event.$stable = 8). Other compiler-emitted deltas: `write$Self$app` → `write$Self$<module>`,
removed `access$...$cp` accessors.

**The jar plugin cannot publish android-only modules** (:core:notification, :media:*) — it resolves
only jvm variants. Feature androidMain classes in package com.lagradost.cloudstream3 also fail it.

Decisions raised (2026-09-17): $stable policy; UiText (A additive new type / B expect-actual /
C versioned break / D A-then-C / E android-module merge); jar merge policy for android-only modules;
plus deferred ones — TestingUtils vs §3.1 rule 2, fcast Packets home, Sync/Subtitle API shape,
ExtractorUri, preference backend (DataStore stores JSON strings in `rebuild_preference`),
RequestsHelper Context-free builder, R ids in provider ABIs, SSLTrustManager trust-all +
SearchSuggestionApi hardcoded TMDB key. Part of [[cloudstream4-kmp-modules]].

**User decisions, 2026-09-17 (settled):**
1. **$stable:** every :core:*/:media:* module that receives classes from Compose-compiled :app applies
   `cloudstream.kmp.compose`, so `$stable`/`@StabilityInferred` survive byte-identically.
2. **UiText:** option D — add a new Context-free text type (outside com.lagradost.cloudstream3, so not
   frozen API) with an :app adapter, mark `utils.UiText`/`txt`/`setText` `@Deprecated(WARNING)` pointing
   at it, and remove the old type in a later release behind an extension-API version bump.
3. **Jar:** extend `cloudstream.extension-api-jar` to merge the compiled Android classes of android-only
   modules (as :app's own are), so :core:notification / :media:* can take extension-facing classes
   without ABI change.

**State 2026-09-17:** PR #4 (`:core:common`, jar plugin) was **merged without the $stable fix**.
The fix is **PR #5** (`fix/core-common-stable`): new convention plugin `cloudstream.kmp.extension-api`
(Compose compiler + compose-runtime only + jvmCommonMain) — use it for every module that receives
:app classes, NOT `cloudstream.kmp.compose` (that one `api`s the whole UI bundle). The pre-move
reference classes used for ABI diffs: main checkout
`app/build/intermediates/built_in_kotlinc/stableDebug/compileStableDebugKotlin/classes` (stale build,
don't rely on it forever); compare with `javap -v -p -c` with `#NN` indices stripped.

**Strings decision (user, 2026-09-17) — supersedes ui-components.md's composeResources ban and
handover §10:** Compose Multiplatform resources become the source of truth for ALL user-facing
strings. The new common text type wraps compose `StringResource` (+ raw text). All 734 strings and
their 62 locale dirs move into a composeResources module and Weblate is to be repointed at those
files (hosted.weblate.org config is outside the repo — the owner does that). The rule in
`.claude/rules/ui-components.md` is to be rewritten to match. Constraints found: :app's XML
(145 layouts, menus, manifest) still needs Android `@string` → generate `res/values*` from
composeResources at build time; `R$string` ids are `public static final int` constants inlined into
extensions, so the generated set must keep ids stable. `:shared` already has one
composeResources/strings.xml. PR #5 merged; PR #6 = :core:network (DohProviders).

**Strings design result (workflow wf_b892b74b-6ad, task whtftn8wz, 2026-09-17) — critique: NOT sound as
specified.** Confirmed: CMP 1.12.0 accepts all 63 b+ dirs; R$string fields in classes.jar are
non-final (extensions link by NAME; R8 off). Refuted/blocking: (1) Weblate `cmp-resource` format
(translate-toolkit aresource.py) READS with Android quote/whitespace stripping but WRITES CMP escaping →
61 values in 11 locales corrupted on first sync; add-language makes build-breaking dir names; hooks
pull mid-cutover. (2) CMP runtime: plural crash for apc/qt/arz (CMP-7619, fix only on master),
iw/in vs he/id on API 35+, only %N$s/%N$d substituted, StringResource ids have no module package
(cross-module collisions). (3) 33 <array>s + non-alias action_open_play (ar/so/ur) can't move
losslessly. Proposed alternative to discuss: keep Android res as Weblate's source, GENERATE
composeResources (:core:strings) from it with aapt2 as oracle; flip later once translate-toolkit is fixed.

**Strings decisions REVISED (user, 2026-09-17) — supersede the earlier "composeResources is source of
truth":** (1) Android `res/values*` + Weblate stay the SOURCE; `:core:strings` GENERATES
composeResources from them at build time (aapt2 as decoding oracle). Flip direction only after
translate-toolkit's cmp-resource read/write asymmetry is fixed upstream. (2) The new text type resolves
via the COMPOSE ENGINE on every platform (user accepted the CMP 1.12.0 limits). Mitigations inside that
choice: fetch raw string and format in our own common formatter (CMP substitutes only %N$s/%N$d); generate
b+he/b+id copies of iw/in; guard plural lookups for apc/qt/arz until CMP ships the fix (PR 5650).

**Strings S-a owner calls (2026-09-17, settled):** (1) Compose must MATCH ANDROID's fallback exactly —
generator materialises values per AOSP ResTable_config matching (pt-PT→pt-BR, tl≡fil, zh+TW untranslated keys
show English); parity tests cover non-directory-name requests. (2) Content guards FAIL on PR checks,
REPORT (not fail) on pushes to master (Weblate commits there); a push-triggered job flags them.
(3) The 34 `<b>`-styled strings ship as PLAIN TEXT in Compose. (4) Keep the generated class name `Res`
(import aliases where :shared's Res also appears). No new dependency approved or needed.

**B3 approved (owner, 2026-09-18):** move AuthToken/AuthUser/AuthData/AuthLoginResponse to :core:model
jvmCommonMain, accepting the only measured delta: 4 synthetic `write$Self$app` → `write$Self$CloudStream_core_model`
renames in the jar dump. Probe + independent check (task `wczjzy90j`): 0 of 910 `$stable` initialisers change
(no class reads these DTOs' $stable — unlike B2's AuthLoginRequirement), Jackson/kotlinx JSON byte-identical
incl. the persisted `auth_tokens` array with the profilePictureHeader alias, Compose classes byte-identical,
3763/3763 jar entries. Precedent: the committed dump already has 122 `write$Self$CloudStream_library` entries.
Rejected (all measured): @Immutable on the DTOs (recreates the B2 flip: AuthUser/AuthData 8→0), a Compose
stability config (no-op), forcing :core:model's module name to "app" (duplicate META-INF/app.kotlin_module →
makeJar fails), waiting for AuthAPI (pinned to :app by R/Context).

**UiString number formatting + HumanReadable (owner, 2026-09-18):** (a) numbers inside translated strings use a
per-platform locale hook (expect/actual: Android/JVM default Locale, iOS NSNumberFormatter) — covers all 62
locales and keeps the per-call locale; NOT the HumanReadable library, whose number API renders whole numbers
only (no %.2f/%1$d/padding), covers 23 languages and sets locale via global mutable `LibresSettings.languageCode`.
(b) `nl.jacobras:Human-Readable` 1.13.1 (MIT, 229★, active, dep kotlinx-datetime ≥0.7 already in catalog at 0.8.0,
targets jvm/ios/js/wasm — no android variant) is to be INVESTIGATED for file sizes / durations / relative times /
abbreviations that :app hand-rolls, with a before/after comparison per locale before adopting. (c) Compose UI test
dependency approved (test-only) so `UiString.resolve()` gets covered.
