package org.tomitribe.repaint.six.jline;

import java.util.List;

/**
 * The seam, unchanged in shape since chapter 03: producers hand over the
 * model; implementations decide what a terminal (or a pipe) gets to see.
 */
public interface Renderer {

    /** Offer the latest model. Cheap; callable at any rate. */
    void update(List<Job> jobs);

    /** Final frame, tidy stream. */
    void close();
}
