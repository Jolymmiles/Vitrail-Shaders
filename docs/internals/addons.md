# Add-ons

Another mod can feed the pack through `dev.vitrail.api` instead of mixing into Vitrail's own
classes. This page is how Vitrail keeps that package's promises; what an add-on may rely on is the
package's javadoc.

## Finding and registering add-ons

An add-on implements `VitrailAddon`. On Fabric it is declared under the entrypoint `"vitrail"`, on
NeoForge as a `META-INF/services/dev.vitrail.api.VitrailAddon` provider. The loader module lists
them (`VitrailPlatform.addons()`), each created on its own so that one whose class fails to load
costs only itself, and `Vitrail.initClient` hands the list to `AddonRegistry.load`.

`AddonRegistry` takes an add-on whole or not at all: what it registers is gathered while its
`register` runs and joined to the lists only when it returns. A linkage error counts as the
add-on's failure, because that is what an add-on built against another version of this package
throws. Every later call into an add-on goes through `AddonRegistry.each` or `call`: a piece that
throws is cut off for the session, logged once with the add-on's id, and the others still run.

## Defines

## Pack sources

## Images

## Frame stages

## Far terrain

## Terrain meshes
