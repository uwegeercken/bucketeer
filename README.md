# Bucketeer

A web-based S3 object browser supporting listing, filtering, download, export to parquet and favorites.

The S3 prefix can be typed in literally or generated dynamically using functions such as left, right, upper, lower, everyNth and date. Functions can be nested and may have literal suffixes.

---

## Running

```bash
mvn package
java -jar target/bucketeer-0.3.0.jar
```

Open [http://localhost:8080](http://localhost:8080).

### Configuration

S3 servers are configured at runtime via the **Configuration** page (`/config`).
Server credentials are stored encrypted in `~/.bucketeer/servers.json`.

### Encryption key

Bucketeer encrypts S3 credentials at rest. The encryption key is resolved in this order:

1. **Environment variable** `BUCKETEER_ENCRYPTION_KEY` – use this for production or shared deployments
2. **Key file** `~/.bucketeer/encryption.key` – auto-loaded if present
3. **Auto-generated** – on first run, a random 256-bit key is generated and saved to `~/.bucketeer/encryption.key`

The auto-generated key means zero configuration for personal use. For production, set the environment variable:

```bash
export BUCKETEER_ENCRYPTION_KEY=your-secret-key
java -jar target/bucketeer-0.3.0.jar
```

> **Warning:** if the key changes or is lost, existing credentials in `~/.bucketeer/servers.json` can no longer be decrypted. Re-enter server credentials via the Configuration page in that case.

---

## UI Features

### Dark Mode

Click the moon/sun icon in the navigation bar to toggle between light and dark mode.
The preference is saved in the browser and persists across sessions.

### Bucket Dropdown

The bucket selector is a dropdown populated from the configured S3 server.
Select a server first, then choose a bucket from the list.

### Favorites

Favorites save a named combination of **server + bucket + prefix template + key** for quick reuse.
They are stored in the browser's `localStorage` — no server-side state required.

Click a favorite pill to pre-fill the form. Click `×` to delete it.

### Results Panel

- Click the maximize icon to expand the results panel to full width
- Export Parquet is available in the results title bar when results are present
- A resolved prefix popover shows the computed S3 path on hover

---

## Prefix Templates

Prefixes may be entered literally to define a search. Bucketeer uses a **prefix template** to compute the S3 path before listing objects.
A template is a path string where segments (separated by `/`) can contain **function placeholders**.

### Syntax

```
segment1/{functionName(ref, arg1, arg2)}/segment3/
```

- A **literal** segment is used as-is: `myprefix`, `2024`, `ABCDEFGH`
- A **placeholder** is wrapped in `{ }`: `{everyNth(key, 0, 2)}`
- A placeholder can have a **literal suffix**: `{left(key, 3)}-test` → `hel-test`
- Use `\{` to include a literal `{` in the path

### References

Inside a function call, the first argument is a **reference**:

| Reference | Meaning |
|-----------|---------|
| `key`     | The key value entered by the user |
| `p1`, `p2`, ... `pN` | The value of segment N in the template (1-based) |

**Rules for `pN`:**
- A `pN` reference to a **literal** segment is always allowed, regardless of position
- A `pN` reference to a **function** segment is only allowed if N < current position (left-to-right resolution)

### Functions

| Function | Signature | Description |
|----------|-----------|-------------|
| `everyNth(ref, start, step)` | 1 ref + 2 args | Characters at index start, start+step, start+2*step, … |
| `left(ref, n)` | 1 ref + 1 arg | First `n` characters; truncates if shorter |
| `right(ref, n)` | 1 ref + 1 arg | Last `n` characters; truncates if shorter |
| `substring(ref, start, len)` | 1 ref + 2 args | From `start` (0-based), length `len`; truncates if needed |
| `upper(ref)` | 1 ref | Uppercase |
| `lower(ref)` | 1 ref | Lowercase |
| `date(pattern)` | no ref | Current date/time formatted with `pattern` |
| `date(pattern, offset)` | no ref | Current date/time ± offset |

**Date patterns** use Java `DateTimeFormatter` syntax: `yyyy/MM/dd`, `yyyyMMdd`, `yyyy/MM`, etc.

**Date offsets:** `+1d` (days), `-2h` (hours), `+3w` (weeks), `-1M` (months), `+2y` (years)

### Wildcard

A `*` at the end of the **Key** field performs a prefix search:
- Key `ABCDE*` → lists all objects whose key starts with `ABCDE` under the resolved prefix
- Only trailing wildcards are supported (S3 native prefix listing)

### Function chaining

Functions can be nested — the result of the inner function becomes the input of the outer:

```
{outer(inner(ref, args), outerArgs)}
```

The inner function is fully resolved first, then its result is passed as the reference to the outer function.
Chaining is arbitrarily deep.

Examples:
```
{upper(everyNth(key, 0, 2))}        → everyNth result in uppercase
{lower(left(key, 5))}               → first 5 chars in lowercase
{left(everyNth(key, 0, 2), 4)}      → everyNth result, first 4 chars
{upper(everyNth(p3, 0, 2))}         → everyNth on a literal segment, uppercased
```

Note: `date` ignores its reference argument, so `{upper(date(yyyy/MM/dd))}` is valid
but the `upper` has no meaningful effect on a date string containing only digits and separators.

---

## Examples

### 1. Direct literal path
```
Template:  data/2024/01/ABCDEFGH/foo.json
Key:       (empty)
Result:    data/2024/01/ABCDEFGH/foo.json
```
Lists all objects under that exact path.

---

### 2. everyNth key pattern
```
Template:  myprefix/{everyNth(key, 0, 2)}/{key}/
Key:       MTIzLzQ1Ni83ODkvMDEy
Result:    myprefix/MILQN8OkME/MTIzLzQ1Ni83ODkvMDEy/
```
`everyNth(key, 0, 2)` takes every other character (index 0, 2, 4, …) of the key.
Used for even distribution across S3 prefixes to avoid hotspots.

---

### 3. Shortened key from a literal segment
```
Template:  myprefix/{everyNth(p3, 0, 2)}/ABCDEFGH/
Key:       (empty)
Result:    myprefix/ACEG/ABCDEFGH/
```
`p3` references the third segment (`ABCDEFGH`), a literal. No key input needed.

---

### 4. Multiple functions on the same literal
```
Template:  data/{left(p4, 4)}/{everyNth(p4, 0, 2)}/ABCDEFGH/
Key:       (empty)
Result:    data/ABCD/ACEG/ABCDEFGH/
```
Both `left` and `everyNth` reference the literal `ABCDEFGH` at position 4.

---

### 5. Function with literal suffix
```
Template:  testdata/events/shard-00/{left(p2, 5)}-test
Key:       (empty)
Result:    testdata/events/shard-00/event-test
```
`p2` references segment 2 (`events`). The function result `event` is followed by the literal suffix `-test`.

---

### 6. Function suffix with extension
```
Template:  files/{upper(key)}.json
Key:       report
Result:    files/REPORT.json
```

---

### 7. Date-based partitioning
```
Template:  logs/{date(yyyy/MM/dd)}/
Key:       (empty)
Result:    logs/2026/07/04/
```

```
Template:  reports/{date(yyyy/MM, -1M)}/summary/
Key:       (empty)
Result:    reports/2026/06/summary/
```

---

### 8. Combined date and key
```
Template:  data/{date(yyyy/MM/dd)}/{everyNth(key, 0, 2)}/{key}/
Key:       MTIzLzQ1Ni83ODkvMDEy
Result:    data/2026/07/04/MILQN8OkME/MTIzLzQ1Ni83ODkvMDEy/
```

---

### 9. Wildcard search
```
Template:  myprefix/{everyNth(key, 0, 2)}/
Key:       ABCD*
```
Lists all objects under `myprefix/<everyNth result>/` whose key starts with `ABCD`.

---

## S3 Query and Result Filtering

When a search is started, Bucketeer fetches **all matching objects** from S3 by paginating through all result pages. The results are cached in an in-memory [DuckDB](https://duckdb.org/) database for the duration of the session.

Once loading is complete, the results can be filtered without additional S3 requests:

| Filter | Description |
|--------|-------------|
| **Name contains** | Substring match on the object key |
| **Size min / max (KB)** | Filter by object size in kilobytes |
| **Date from / to** | Filter by last-modified date |

Filters are applied instantly with a short debounce delay. Pagination (100 objects per page) is available for large result sets.

A progress indicator shows how many objects have been found while S3 pagination is still running.

---

## Parquet Export

After a query, the current filtered result set can be exported as a **Parquet file** using the **Export Parquet** button. The active filters are applied to the export — what you see is what you get.

The exported file is named automatically:
```
bucketeer-<bucket>-yyyyMMdd_HHmmss.parquet
```

The Parquet file contains the following columns:

| Column | Type | Description |
|--------|------|-------------|
| `key` | VARCHAR | S3 object key |
| `bucket` | VARCHAR | Bucket name |
| `size_bytes` | BIGINT | Object size in bytes |
| `last_modified` | TIMESTAMP | Last modification time |
| `etag` | VARCHAR | S3 ETag |

The file can be used directly with DuckDB, DuckLake, or any other Parquet-compatible tool:

```sql
-- DuckDB example
SELECT * FROM 'bucketeer-mybucket-20260712_143000.parquet';

-- aggregate example
SELECT bucket, COUNT(*) as count, SUM(size_bytes)/1024/1024 as total_mb
FROM 'bucketeer-mybucket-20260712_143000.parquet'
GROUP BY bucket;
```

---

## S3 Server Configuration

S3 servers are managed at runtime via the **Configuration** page (`/config`). No restart is required after adding, editing or deleting a server.

Each server entry supports:

| Field | Description |
|-------|-------------|
| **Name** | Display name used in the server dropdown |
| **Endpoint** | S3-compatible endpoint URL, e.g. `http://localhost:9000` |
| **Region** | AWS region string, e.g. `us-east-1` (required but ignored by most S3-compatible servers) |
| **Access Key** | S3 access key |
| **Secret Key** | S3 secret key |
| **Verify Certificate** | Uncheck for HTTPS servers without a valid certificate (e.g. StorageGRID without cert) |

After saving, a **Save & Test** option verifies the connection by listing buckets before confirming.

Server credentials are stored encrypted in `~/.bucketeer/servers.json`.

---

## Adding a new function

1. Create a class implementing `TemplateFunction` in `domain/template/function/`
2. Annotate with `@Component`
3. Implement `name()`, `expectedArgCount()`, and `apply(resolvedRef, args)`

Spring auto-discovers all `TemplateFunction` beans — no registration needed.

```java
@Component
public class Md5Function implements TemplateFunction {

    @Override public String name() { return "md5"; }
    @Override public int expectedArgCount() { return 0; }

    @Override
    public String apply(String resolvedRef, List<String> args) {
        // compute MD5 of resolvedRef
    }
}
```

Usage in template: `data/{md5(key)}/{key}/`

## Last update
last update uwe.geercken@web.de - 2026-07-18