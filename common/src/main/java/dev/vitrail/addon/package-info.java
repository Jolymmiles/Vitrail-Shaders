/**
 * Vitrail's side of the add-on API in {@code dev.vitrail.api}: who registered what, and the one
 * rule every call into an add-on goes through.
 * <p>
 * Apart from the API because nothing here is a promise to another mod. What an add-on may rely on
 * is in the API's own javadoc; this package is how Vitrail keeps those promises and may change
 * whenever that is easier.
 */
package dev.vitrail.addon;
