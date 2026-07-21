package io.github.uwegeercken.bucketeer.adapter.in.web;

import java.io.Serializable;
import java.util.Map;

public record QueryParams(
        String serverName,
        String bucket,
        String prefix,
        String key,
        String dateFrom,
        String dateTo,
        String whereClause
) implements Serializable {

    public Map<String, String> toMap() {
        return Map.of(
                "serverName",  serverName  != null ? serverName  : "",
                "bucket",      bucket      != null ? bucket      : "",
                "prefix",      prefix      != null ? prefix      : "",
                "key",         key         != null ? key         : "",
                "dateFrom",    dateFrom    != null ? dateFrom    : "",
                "dateTo",      dateTo      != null ? dateTo      : "",
                "whereClause", whereClause != null ? whereClause : ""
        );
    }
}
