package org.tomitribe.repaint.six;

import org.tomitribe.crest.api.Loader;

import java.util.Iterator;
import java.util.List;

/**
 * Chapters 01-05 let the crest-maven-plugin's descriptor goal discover
 * command classes by scanning bytecode at build time — but that scanner
 * can't read Java 22 class files yet. This is crest's other, older door:
 * a hand-written Loader registered via ServiceLoader.
 */
public class SixLoader implements Loader {

    @Override
    public Iterator<Class<?>> iterator() {
        return List.<Class<?>>of(Probe.class, Watch.class, Tasks.class).iterator();
    }
}
