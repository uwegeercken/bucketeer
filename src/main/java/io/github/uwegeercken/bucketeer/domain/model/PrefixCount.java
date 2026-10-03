package io.github.uwegeercken.bucketeer.domain.model;

/**
 * Number of sub-prefixes found below a given prefix.
 *
 * @param count  number of common prefixes found
 * @param capped true if the count was stopped early at an internal limit
 */
public record PrefixCount(int count, boolean capped) {
}