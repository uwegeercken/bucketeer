# Bucketeer

A web-based S3 object browser supporting listing, download, favorites, and flexible prefix templates.
Supports Minio (development) and NetApp StorageGRID (production).

---

## Running

```bash
mvn package
java -jar target/bucketeer-0.1.0.jar
```

Open [http://localhost:8080](http://localhost:8080).

### Configuration (`application.yml`)

```yaml
bucketeer:
  servers:
    - name: "Local Minio"
      endpoint: http://localhost:9000
      region: us-east-1
      access-key: ${S3_ACCESS_KEY:minioadmin}
      secret-key: ${S3_SECRET_KEY:minioadmin}
    - name: "StorageGRID Prod"
      endpoint: https://storagegrid.intern:10443
      region: us-east-1
      access-key: ${S3_ACCESS_KEY_PROD}
      secret-key: ${S3_SECRET_KEY_PROD}
```

---

## Prefix Templates

Bucketeer uses a **prefix template** to compute the S3 path before listing objects.
A template is a path string where segments (separated by `/`) can contain **function placeholders**.

### Syntax

```
segment1/{functionName(ref, arg1, arg2)}/segment3/
```

- A **literal** segment is used as-is: `myprefix`, `2024`, `ABCDEFGH`
- A **placeholder** is wrapped in `{ }`: `{shortenedKey(key)}`
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
| `shortenedKey(ref)` | 1 ref | Characters at index 0, 2, 4, … |
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

### 2. Shortened key pattern
```
Template:  myprefix/{shortenedKey(key)}/{key}/
Key:       MTIzLzQ1Ni83ODkvMDEy
Result:    myprefix/MILQN8OkME/MTIzLzQ1Ni83ODkvMDEy/
```
`shortenedKey` takes every other character (index 0, 2, 4, …) of the key.
Used for even distribution across S3 prefixes to avoid hotspots.

---

### 3. Shortened key from a literal segment
```
Template:  myprefix/{shortenedKey(p3)}/ABCDEFGH/
Key:       (empty)
Result:    myprefix/ACEG/ABCDEFGH/
```
`p3` references the third segment (`ABCDEFGH`), a literal. No key input needed.

---

### 4. Multiple functions on the same literal
```
Template:  data/{left(p3, 4)}/{shortenedKey(p3)}/ABCDEFGH/
Key:       (empty)
Result:    data/ABCD/ACEG/ABCDEFGH/
```
Both `p2` and `p3` reference the literal `ABCDEFGH` at position 4.

---

### 5. Date-based partitioning
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

### 6. Combined date and key
```
Template:  data/{date(yyyy/MM/dd)}/{shortenedKey(key)}/{key}/
Key:       MTIzLzQ1Ni83ODkvMDEy
Result:    data/2026/07/04/MILQN8OkME/MTIzLzQ1Ni83ODkvMDEy/
```

---

### 7. Wildcard search
```
Template:  myprefix/{shortenedKey(key)}/
Key:       ABCD*
```
Lists all objects under `myprefix/<shortenedKey>/` whose key starts with `ABCD`.

---

## Favorites

Favorites save a named combination of **server + bucket + prefix template + key** for quick reuse.
They are stored in the browser's `localStorage` — no server-side state required.

Click a favorite pill to pre-fill the form. Click `×` to delete it.

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
