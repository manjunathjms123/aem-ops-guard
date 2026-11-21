# aem-ops-guard

A Java command-line tool that checks Adobe Experience Manager (AEM) environments for problems before releases.

## Scope

Three checks:

1. **Author sanity:** automated check that author pages work.
2. **OSGi R6 config:** validates bundles and configuration.
3. **User permissions:** audits user groups and permissions, and saves environment snapshots as JSON or Oak files.

Later: a drift check that compares QA, UAT, and Production before a release.

Targets: local, QA, UAT, and Cloud AEM instances, through the Sling and Felix endpoints.

## Build order

| Week | Work |
|------|------|
| 1 | Scope and repo setup |
| 2 | HTTP client for Sling and Felix endpoints |
| 3 | Module 1: Author sanity; `sanity` command |
| 4 | Module 2: OSGi R6 config |
| 5 | Module 3: User permissions and snapshots |
| 6 | Drift check across QA, UAT, Production |
| 7 | Testing; JUnit coverage above 80% |
| 8 | Packaged JAR and live demo |

## Requirements

- Java 21
- Maven 3.9+

## Layout

```
config/*.env.example                     one settings file per environment (templates)
src/main/java/com/example/aemopsguard/   application code
src/test/java/com/example/aemopsguard/   JUnit 5 tests
```

## Setup

Each environment has its own settings file in `config\`:

| Environment | Settings file | Password variable |
|---|---|---|
| local | `config\local.env` (password in the file, or `AEM_LOCAL_PASSWORD`) | `AEM_LOCAL_PASSWORD` |
| qa | `config\qa.env` | `AEM_QA_PASSWORD` |
| uat | `config\uat.env` | `AEM_UAT_PASSWORD` |
| cloud | `config\cloud.env` | `AEM_CLOUD_PASSWORD` |

1. Copy the matching `.env.example` file to `.env` (for example `config\qa.env.example` to `config\qa.env`), and fill in its URL, user, and pages.
2. Set that environment's password as an environment variable. Passwords never go in the files.

The real `config\*.env` files are git-ignored. The `.example` files are tracked.

## Build

```cmd
mvn verify
```

## Automated PR check (GitHub Actions)

`.github/workflows/pr-check.yml` runs on every pull request to `main` or `develop`.

Branches:

- `main` holds released code only. Change it through a pull request from `develop`.
- `develop` is where code changes land. Create feature branches from `develop` and open pull requests into it.

1. **Build, test and coverage:** `mvn clean verify` runs all JUnit tests and fails the build if line coverage drops below 80%. The JaCoCo report is uploaded as a build artifact. The JAR is then packaged.
2. **Claude code review:** `anthropics/claude-code-action` reviews the diff for correctness, secret handling, plain HTTP and TLS, and missing tests, and posts inline comments.

Setup, once per repository:

1. Add a repository secret named `ANTHROPIC_API_KEY` (Settings, Secrets and variables, Actions).
2. Install the Claude GitHub app on the repository, or let the action prompt for it on the first run.

Pull requests from forks skip the Claude review job, because secrets are not passed to forks.

## Build the JAR

```cmd
mvn clean package
```

This creates `target\aem-ops-guard.jar`, which runs with Java 21 and has no other dependencies.

## Test coverage (JUnit and JaCoCo)

Run the tests and the coverage report together. `verify` runs every JUnit test, and JaCoCo writes the report:

```cmd
mvn clean verify
```

Open the HTML report in a browser:

```cmd
start target\site\jacoco\index.html
```

To print the totals and each package's line coverage without opening the browser:

```powershell
$rows = Import-Csv target\site\jacoco\jacoco.csv
$m = [int](($rows | Measure-Object LINE_MISSED -Sum).Sum)
$c = [int](($rows | Measure-Object LINE_COVERED -Sum).Sum)
"Line coverage: {0:P1} ({1} of {2} lines)" -f ($c / ($m + $c)), $c, ($m + $c)
$rows | Group-Object PACKAGE | ForEach-Object {
    $pm = [int](($_.Group | Measure-Object LINE_MISSED -Sum).Sum)
    $pc = [int](($_.Group | Measure-Object LINE_COVERED -Sum).Sum)
    "{0,-50} {1,6:P1}" -f $_.Name, ($pc / ($pm + $pc))
}
```

The coverage target from the plan is above 80%. Latest run (103 tests, all passing):

| Measure | Result |
|---|---|
| Line coverage | 85.7% (728 of 849 lines) |
| Branch coverage | 81.6% (302 of 370 branches) |

Lowest-covered areas: `SnapshotCollector` (74.5%), `PermissionAudit` (77.6%), `AemHttpClient` (78.0%), and `Main` (79.1%). 
Most of the gap is error handling for endpoints that fail or return unexpected data.

The numbers above are from the last run. They change as code changes, so rerun `mvn clean verify` to refresh them.

## Run

Run these from the `aem-ops-guard` folder, so `config\` is found:

```cmd
java -jar target\aem-ops-guard.jar --env local sanity
java -jar target\aem-ops-guard.jar --env qa sanity
java -jar target\aem-ops-guard.jar --env qa sanity
java -jar target\aem-ops-guard.jar --config D:\other\qa.env --env qa sanity
```

`--env NAME` loads `config\NAME.env`. `--config FILE` overrides that path.

### Per-environment endpoints

Each environment file sets its own host and endpoints, so Cloud can differ from on-premise QA or UAT:

| Key | Meaning | Default |
|---|---|---|
| `AEM_<NAME>_URL` | Host, plus an optional context path such as `https://host/prefix` | none (required) |
| `AEM_<NAME>_PING_PATH` | Felix bundle list used by `sanity` (OSGi R6 module) and `felix` | `/system/console/bundles.json` |
| `AEM_<NAME>_SITE_PAGE` | Author page paths that `sanity` checks, comma-separated (set in the config file only) | none (required for `sanity`) |

Paths must start with `/`. Cloud may restrict the Felix console, so set `AEM_CLOUD_PING_PATH` to a path your Cloud environment allows.

- `sanity` runs all three modules and prints one list grouped by module, then a total such as `Result: 7/9 checks passed`:
  1. **Author sanity:** each page in `AEM_<NAME>_SITE_PAGE` must return HTTP 200, an HTML body, and no login form.
  2. **OSGi R6:** each bundle in `AEM_<NAME>_OSGI_REQUIRED_BUNDLES` must be installed, in the given state (default `Active`), and at the given version if one is listed. Entries use `symbolicName[:version[:state]]`, for example `aem-guides-wknd.core:4.0.4:Active`, and each PID in `AEM_<NAME>_OSGI_CONFIG_PIDS` must have a configuration. Other non-Active bundles are counted for information only.
  3. **Permissions:** the current-user endpoint must report the configured user, and the user must belong to each group in `AEM_<NAME>_EXPECTED_GROUPS`.
- `--snapshot` also saves the results and the raw bundle and user data to `snapshots\<env>-<time>.json`.
- `felix` requests each Felix console endpoint and reports which ones answer with 
- HTTP 2xx: console root, bundles, components, Configuration Manager, services, log levels, memory, threads, Sling settings, Sling jobs, Sling events, the Sling log viewer, request history, JCR resolver, servlet resolver, Sling Models, adapters, and filters (18 endpoints in total). 
- Each path can be overridden per environment with `AEM_<NAME>_FELIX_<KEY>_PATH`, as listed in the config templates. Bundles and Configuration Manager use the existing `PING_PATH` and `OSGI_CONFIG_PATH` keys.
  - Some endpoints, such as memory and threads, can be heavy on a busy author. Run `felix` against QA before production.
  - Cloud may block some console paths. `felix` shows which ones are blocked, so you can remove them from the list for that environment.

- `login` checks the login form and that the configured user can log in:
  1. `AEM_<NAME>_LOGIN_PATH` (default `/libs/granite/core/content/login.html`) must load with HTTP 200 and contain the username field.
  2. The username and password are posted to `AEM_<NAME>_LOGIN_SUBMIT_PATH` (default `/j_security_check`) as a browser form would. AEM must issue a `login-token` cookie.

  The password is sent only in that form post, and it is never printed. The login request sends no Basic auth header, so a success really means the form login worked. `login` is a separate command and is not part of `sanity`, because it posts the password each time it runs.

### Release diff: snapshot and diff

Capture each environment's state to a JSON file, then compare two files. Use this to check a release: take a snapshot before the release and another after it, or one from each environment.

```cmd
java -jar target\aem-ops-guard.jar snapshot --env qa  --out qa.json
java -jar target\aem-ops-guard.jar snapshot --env uat --out uat.json
java -jar target\aem-ops-guard.jar diff qa.json uat.json --allow allow.yaml --report report.json
```

`snapshot` runs the sanity checks and saves the results and raw data to the file, and it exits 0 even if some checks fail (the failures are recorded in the file).

`diff` compares the two files on author pages, bundle states, configured services, the current user, and the user and group counts. Each check prints as `SAME`, `ALLOWED`, or `DRIFT`. Exit codes: 0 when there are no differences that aren't allowed, 1 when there are, 2 when a file can't be read or the allow file is invalid. Use the exit code as the release gate.

**Allow file (`allow.yaml`):** approved differences, so an expected change doesn't fail the gate. Each entry needs a `reason`, so every approval says why it was made:

```yaml
allow:
  - category: bundle state          # the check name shown in the diff
    key: aem-guides-wknd.core       # optional: one item; leave out to allow the whole check
    reason: "Approved in CHG-1234"
  - category: current user
    reason: "Service account rotated"
```

`--report FILE` writes the result as JSON: each check with its status and detail, plus the counts and an overall `PASS` or `FAIL`.

### Drift check before a release

`drift` reads the same data from several environments and reports where they differ. The first environment is the baseline, and each other environment is compared with it:

```cmd
java -jar target\aem-ops-guard.jar --envs qa,uat,prod drift
```

It compares:

- **Bundle states:** each symbolic name and its state (for example `Active` or `Resolved`).
- **Configurations:** which services have a configuration.
- **Groups:** which groups exist under `/home/groups`.
- **User and group counts** from the query builder.

Each comparison is one line, marked `SAME` or `DRIFT`, with the values that differ. A value that can't be read is reported as a failure, not as a match. Exit codes: 0 when everything matches, 1 when drift is found, 2 when an environment can't be read. Use the exit code in a release script to stop a release.

Each environment is read from its own file (`config\<env>.env`), so each one needs its own password variable.

**Plain HTTP:** only LOCAL may use `http://` by default. To compare instances that are reachable only over plain HTTP, set `AEM_<NAME>_URL=http://host:port` and `AEM_<NAME>_ALLOW_HTTP=true` in that environment's file. Every run that uses plain HTTP prints a warning, because the username and password are sent unencrypted. Use this only on a trusted network. Use `--config-dir FOLDER` to read the files from another folder. Production is set up in `config\prod.env.example`.

### Endpoints per environment

Every endpoint is read from the environment's own file, so Cloud can differ from QA or UAT:

| Key | Default |
|---|---|
| `AEM_<NAME>_URL` | required; host with optional context path |
| `AEM_<NAME>_PING_PATH` | `/system/console/bundles.json` |
| `AEM_<NAME>_OSGI_CONFIG_PATH` | `/system/console/configMgr` |
| `AEM_<NAME>_CURRENT_USER_PATH` | `/libs/granite/security/currentuser.json` |

Paths must start with `/`. The default paths are standard AEM endpoints, but Cloud may restrict some of them. Confirm each path on the target environment before relying on it.

### Limits so far

- The OSGi module checks that configurations exist.
- The permission module checks group membership, not the permissions granted on each path.

Exit codes: 0 success, 1 a check or request failed, 2 usage or configuration error.
