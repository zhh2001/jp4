# Releasing jp4 to Maven Central

This guide is the per-release checklist for a jp4 maintainer. It covers the
one-time setup (Sonatype Central Portal account, GPG key, Gradle credentials),
the per-release procedure (version bump, local dry-run, real upload, manual
publish from the Portal UI, GitHub release tag), and a few common failure
modes with their fixes.

> **Audience:** the project maintainer (zhh2001). End users do not need to
> read this — they consume jp4 from Maven Central via the coordinates listed
> in the [README](README.md).

## Background

OSSRH (`s01.oss.sonatype.org`) was sunset on 2025-06-30. All publishing now
goes through the **Central Publisher Portal** at
<https://central.sonatype.com>; existing OSSRH namespaces were migrated
automatically. jp4 uses the
[`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
Gradle plugin, which targets the Portal API.

## One-time setup

### 1. Verify the namespace

`io.github.zhh2001` is auto-verified by Sonatype because the GitHub
account `zhh2001` owns the corresponding `<username>.github.io` domain.
Confirm it's listed under "Namespaces" at
<https://central.sonatype.com>:

1. Sign in via "Sign in with GitHub".
2. Click your username (top right) → "View Namespaces".
3. `io.github.zhh2001` should be listed and verified.

If it's not verified for some reason, follow the instructions at
<https://central.sonatype.org/register/namespace/>.

### 2. Generate a User Token

The publish API needs a user token, **not** the GitHub password:

1. <https://central.sonatype.com> → username → "Generate User Token".
2. Click "Generate User Token". Two strings come out — a username-like
   value and a password-like value. **Copy them now**; the password
   cannot be re-shown later.

### 3. Generate a GPG key for signing

Maven Central rejects unsigned artifacts. ED25519 is not supported; use RSA.

```bash
gpg --full-generate-key
# Choose: (1) RSA and RSA, 4096 bits, 2y expiration (or 0 = never)
# Real name: zhh2001
# Email: zhh2001@users.noreply.github.com   (the address in the POM)

# List your keys to find the LONG key id:
gpg --list-secret-keys --keyid-format LONG

# Upload the public key to a keyserver so verifiers can fetch it:
gpg --keyserver keyserver.ubuntu.com  --send-keys <LONG_KEY_ID>
gpg --keyserver keys.openpgp.org      --send-keys <LONG_KEY_ID>

# Export the *armored secret key* — the plugin reads this:
gpg --armor --export-secret-keys <LONG_KEY_ID> > /tmp/jp4-signing-key.asc
```

Keep `/tmp/jp4-signing-key.asc` somewhere safe (encrypted disk or
password manager). It is the one credential you must not lose.

### 4. Configure Gradle credentials

Put the credentials in **`~/.gradle/gradle.properties`** (user-home, never in
the repo). The repo's `.gitignore` excludes `/gradle.properties` defensively
but the canonical place for these properties is the user-home file.

```properties
# ~/.gradle/gradle.properties

# Central Portal token (from step 2)
mavenCentralUsername=<token-username>
mavenCentralPassword=<token-password>

# GPG signing (from step 3 — paste the *content* of jp4-signing-key.asc on a
# single line, with literal "\n" replacing real newlines)
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n…\n-----END PGP PRIVATE KEY BLOCK-----
signingInMemoryKeyId=<LAST_8_HEX_OF_LONG_KEY_ID>
signingInMemoryKeyPassword=<your GPG passphrase>
```

The plugin's docs have a more detailed walkthrough including how to use a
key file path instead of an inline key — see
<https://vanniktech.github.io/gradle-maven-publish-plugin/central/>.

### 5. (Optional) Verify the setup with a local dry-run

Without uploading anywhere, generate the artifacts you'd publish:

```bash
./gradlew publishToMavenLocal
ls ~/.m2/repository/io/github/zhh2001/jp4/0.1.0-SNAPSHOT/
# Expect: jp4-0.1.0-SNAPSHOT.jar, .pom, -sources.jar, -javadoc.jar, .module
```

If the GPG key is configured in `~/.gradle/gradle.properties`, the artefacts
are also signed (`*.asc` files appear). If not, they aren't — both states
work for the local dry-run; only Central upload requires signing.

## Per-release procedure

For each release of jp4 (v0.1.0, v0.1.1, …):

### 1. Update version + CHANGELOG

```bash
# Bump the version in build.gradle.kts (drop the -SNAPSHOT suffix for a release).
# Move the [Unreleased] entries in CHANGELOG.md to a [0.1.0] section, dated.
```

Run the test suite locally to confirm green:

```bash
./gradlew clean build
```

### 2. Local dry-run (catches POM regressions before they hit the wire)

```bash
./gradlew publishToMavenLocal
```

Inspect the generated POM for regressions:

```bash
cat ~/.m2/repository/io/github/zhh2001/jp4/<version>/jp4-<version>.pom
```

Required fields: `<name>`, `<description>`, `<url>`, `<licenses>`,
`<scm>`, `<developers>`. CI's `publish-dry-run` job validates these on
every push, so a missing field is normally caught earlier; this step
is double-check before the real upload.

### 3. Upload to Maven Central staging

```bash
./gradlew publishToMavenCentral
```

This:

1. Generates artefacts (jar / sources jar / javadoc jar / pom).
2. Signs them with the configured GPG key.
3. Uploads to `https://central.sonatype.com/api/v1/publisher/upload`.
4. Returns a deployment ID.

### 4. Inspect + publish from the Portal UI

`automaticRelease = false` is set in `build.gradle.kts`, so the upload
lands in **staging** — it's not yet visible on Maven Central:

1. Sign in to <https://central.sonatype.com>.
2. Navigate to "Deployments" — find the deployment with your ID.
3. Inspect the file list (jar, sources, javadoc, pom, signatures).
4. Click "Publish" to release it to Maven Central.

(Once the release ritual is well-trodden, you can change
`automaticRelease = true` in `build.gradle.kts` to skip the manual click.
For v0.1.0 the manual review is the safer default.)

### 5. Wait for indexing

After clicking Publish, the artefact appears on Maven Central within ~10-30
minutes. Confirm via:

```bash
curl -s "https://search.maven.org/solrsearch/select?q=g:io.github.zhh2001+a:jp4&core=gav&rows=5&wt=json" \
  | python3 -c 'import json,sys; print([d["v"] for d in json.load(sys.stdin)["response"]["docs"]])'
```

When your version shows up, the release is live.

### 6. Tag + GitHub release

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

On GitHub: <https://github.com/zhh2001/jp4/releases/new> → choose the
`v0.1.0` tag → title `jp4 v0.1.0` → paste the relevant CHANGELOG section
into the body → Publish.

### 7. Bump to next snapshot

Edit `build.gradle.kts`: `version = "0.1.1-SNAPSHOT"` (or whatever's next).
Add a fresh `[Unreleased]` section to `CHANGELOG.md`. Commit.

## Common failure modes

| Symptom | Diagnosis | Fix |
|---|---|---|
| `403` from Central Portal | User token wrong / expired / not generated for this account | Regenerate at <https://central.sonatype.com> → Generate User Token |
| `Validation failed: missing pom field <name>` | POM is incomplete | Check `mavenPublishing { pom { … } }` in `build.gradle.kts` |
| `Signing failed: key not found` | GPG key not configured / wrong key id | Verify `~/.gradle/gradle.properties` matches `gpg --list-secret-keys` output; key id is the **last 8 hex chars** of the LONG id |
| `Validation failed: signature verification failed` | Public key not on a keyserver | Re-run `gpg --send-keys <LONG_KEY_ID>` to a public keyserver and retry |
| `Could not resolve com.vanniktech.maven.publish:0.33.0` | Plugin Portal unreachable | Check network; the plugin lives on the Gradle Plugin Portal |
| Deployment stuck in "VALIDATING" forever | Upstream Portal API slow | Wait 5-10 minutes; if no progress, drop the deployment via Portal UI and retry |

## Files involved

- [`build.gradle.kts`](build.gradle.kts) — `mavenPublishing { … }` block.
- [`CHANGELOG.md`](CHANGELOG.md) — release notes referenced from the GitHub release.
- [`.github/workflows/ci.yml`](.github/workflows/ci.yml) — `publish-dry-run`
  job validates the POM on every push.
- [`~/.gradle/gradle.properties`](~) — secrets (NEVER in this repo).

## Reference

- Sonatype Central Portal: <https://central.sonatype.com>
- Central Portal Publisher API:
  <https://central.sonatype.org/publish/publish-portal-api/>
- vanniktech plugin docs:
  <https://vanniktech.github.io/gradle-maven-publish-plugin/central/>
- OSSRH End-of-Life notice:
  <https://central.sonatype.org/pages/ossrh-eol/>
