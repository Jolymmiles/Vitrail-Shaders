package dev.vitrail.api;

/**
 * Which pack is being opened, as an add-on can tell packs apart.
 *
 * @param name the folder or zip name without its extension, as the pack list shows it
 * @param contentHash a hex SHA-256 over the pack's files under {@code shaders/}, paths and
 *     contents, so that two copies of one release match and an edited copy does not
 */
public record PackIdentity(String name, String contentHash) {
}
