---
paths:
  - "app/src/main/AndroidManifest.xml"
  - "app/src/main/java/com/lagradost/cloudstream3/**/*DeepLink*.kt"
  - "app/src/main/res/navigation/**"
---
# Deep Linking Rules

## URL Schemes

CloudStream does not have one scheme — it has **six custom schemes, one per intent**, declared in
`app/src/main/AndroidManifest.xml`:

| Scheme | Opens |
|---|---|
| `cloudstreamapp://` | the app, generic entry |
| `cloudstreamsearch://` | a search for a query |
| `cloudstreamplayer://` | the player on a link |
| `cloudstreamrepo://` | add a repository — the one that matters most for first run |
| `cloudstreamcontinuewatching://` | resume a title |
| `csshare://` | a shared item |

Plus `magnet:` and `content:` for torrents and file handoff.

There is **no `APP_SCHEMA` constant**. Each scheme is a literal in the manifest and is matched by
hand at its handler. Adding a seventh scheme without a constant is how you end up with a typo that
only fails on a device, so if you touch this area, propose lifting the six into one object first
rather than adding to the pattern.

## Adding New Deep Links

1. Add `navDeepLink` entries to the destination's `composable` call
2. Use BOTH `APP_URL` and `APP_SCHEMA` patterns
3. For parameterized routes: `${APP_URL}path/{param}?query={value}`

```kotlin
composable<FeatureDestination>(
    deepLinks = listOf(
        navDeepLink { uriPattern = "${APP_URL}feature" },
        navDeepLink { uriPattern = "${APP_SCHEMA}feature" }
    )
) { ... }
```

## Architecture Flow

```
URL/Notification -> ExternalUriHandler.onNewUri() -> [Cache URI]
-> NavHost ready -> navController.navigate(NavUri(uri))
```

Cold start: listener set up AFTER NavHost emits first back stack entry.
