package io.github.uwegeercken.bucketeer.domain.model;

/**
 * Helpers for S3 entity tags.
 *
 * <p>S3 delivers the ETag as a quoted HTTP header value (e.g. {@code "abc123"}); the quotes are
 * part of the HTTP header syntax, not of the tag itself. Bucketeer strips them at the boundary so
 * the stored value is clean and survives CSV exports (an embedded quote would otherwise be
 * re-escaped by the CSV writer into {@code """abc123"""}).</p>
 */
public final class Etags {

    private Etags() {
    }

    /**
     * Removes the surrounding double quotes S3 puts around an ETag. {@code null} stays
     * {@code null}, and values without the surrounding quotes are returned unchanged.
     */
    public static String stripQuotes(String etag) {
        if (etag == null || etag.length() < 2 || etag.charAt(0) != '"' || etag.charAt(etag.length() - 1) != '"') {
            return etag;
        }
        return etag.substring(1, etag.length() - 1);
    }
}
