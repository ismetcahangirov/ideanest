package az.ideanest.discovery.domain;

/**
 * One entry in V16's gazetteer, named in the reader's language — §4.3's {@code ?city=}
 * vocabulary, published by {@code GET /v1/locations} (#276).
 *
 * <p><strong>A slug and a name, and deliberately not a coordinate.</strong> {@code
 * locations} also holds a latitude and a longitude, and this record drops them. They exist
 * to answer §4.3's proximity filter inside a query, and the two clients that need this list
 * — the profile editor's location control and, when it is built, discovery's own city
 * facet — are both choosing a name from a list. Publishing the pair would put eighteen city
 * centroids in a public cacheable body for no caller, and V16 argues at length that the
 * precision of those columns is a privacy decision rather than a storage one.
 *
 * @param slug the stable handle, and what {@code ?city=} and {@code locationSlug} match on.
 *     §11.3's fold, so {@code Bakı}, {@code BAKI} and {@code baki} are one value
 * @param name in the negotiated language, falling back to the {@code az} endonym and then
 *     to the slug — the chain {@code Taxonomy} states and the same one the categories read
 *     uses, because a client that had to implement a second fallback would eventually
 *     implement a different one
 */
public record Place(String slug, String name) {
}
