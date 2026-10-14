/**
 * The offline map baker ({@code docs/DESIGN-M3.md} REQ-M3-003, REQ-M3-024, API-M3-003): reads an edit source or a
 * generator, lights it with the light engine and writes a map release of {@code .synx} region files, the far view
 * packs of API-M3-014 and a {@code manifest.json}; {@code verify} checks a release and {@code farview-export}
 * names its packs by digest for the CDN.
 */
package io.github.bearisbug.synaxis.baker;
